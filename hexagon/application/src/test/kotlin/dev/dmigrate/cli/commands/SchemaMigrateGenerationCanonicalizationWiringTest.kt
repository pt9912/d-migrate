package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.RenameProjectionCapabilities
import dev.dmigrate.core.diff.migration.overlay.MigrationOverlayDocument
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

/**
 * `canonicalizeGeneration` ist an vier Naehten ein Parameter mit Default
 * `{ it }` (`MigrationFingerprint.compute`/`.project`, `DiffPlanner.plan`,
 * `SchemaMigrateExecutionStage.runPostCompare`). Ein Aufrufer, der ihn
 * weglaesst, kompiliert -- die Auslassung ist syntaktisch unsichtbar, und
 * ein Test, der nur den Kanonisierer selbst prueft, bliebe gruen.
 *
 * Dieser Test prueft deshalb die **Verdrahtung**: was der Runner dem
 * Planer tatsaechlich mitgibt, und was in den Endpunkt-Abdruecken landet.
 * Streicht man das Argument an einer der Aufrufstellen im
 * [SchemaMigrateRunner], faellt er.
 */
class SchemaMigrateGenerationCanonicalizationWiringTest : FunSpec({

    val tmpDir = Files.createTempDirectory("migrate-generation-wiring")
    val sourcePath = tmpDir.resolve("source.yaml")
    val targetPath = tmpDir.resolve("target.yaml")
    Files.writeString(sourcePath, "# source")
    Files.writeString(targetPath, "# target")

    val systemGenerated = ColumnGeneration.Identity(
        mode = IdentityMode.ALWAYS,
        sequenceName = "ISEQ\$\$_73345",
    )

    fun schemaWithIdentity(generation: ColumnGeneration?) = SchemaDefinition(
        name = "App",
        version = "1",
        tables = mapOf(
            "orders" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(type = NeutralType.Identifier(), generation = generation),
                ),
                primaryKey = listOf("id"),
            ),
        ),
    )

    /**
     * Faengt ab, was der Runner als vierten Kanonisierer durchreicht, und
     * gibt den Plan unveraendert weiter.
     */
    class CapturingPlanner : DiffPlanner() {
        var captured: ((ColumnGeneration?) -> ColumnGeneration?)? = null

        override fun plan(
            current: SchemaDefinition,
            desired: SchemaDefinition,
            schemaDiff: SchemaDiff,
            migrationOverlays: List<MigrationOverlayDocument>,
            capabilities: RenameProjectionCapabilities,
            triggerPlanningContext: dev.dmigrate.core.diff.migration.TriggerPlanningContext,
            canonicalizeType: (NeutralType) -> NeutralType,
            canonicalizeIndex: (IndexDefinition) -> IndexDefinition,
            canonicalizeGeneration: (ColumnGeneration?) -> ColumnGeneration?,
        ): DiffResult {
            captured = canonicalizeGeneration
            return super.plan(
                current, desired, schemaDiff, migrationOverlays, capabilities, triggerPlanningContext,
                canonicalizeType, canonicalizeIndex, canonicalizeGeneration,
            )
        }
    }

    fun runnerFor(planner: CapturingPlanner): Pair<SchemaMigrateRunner, StringBuilder> {
        val stdout = StringBuilder()
        val runner = SchemaMigrateRunner(
            fileLoader = { op ->
                // Soll ohne Sequenznamen (ein Anwender kann ihn nicht
                // kennen), Ist mit -- genau die Konstellation, die ohne den
                // Hook falsche Drift meldet.
                val schema = if (op.path == sourcePath) {
                    schemaWithIdentity(ColumnGeneration.Identity(mode = IdentityMode.ALWAYS, sequenceName = null))
                } else {
                    schemaWithIdentity(systemGenerated)
                }
                ResolvedSchemaOperand(
                    reference = "file:${op.path.fileName}",
                    schema = schema,
                    validation = ValidationResult(),
                )
            },
            dbLoader = null,
            normalizer = { it },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            planner = planner,
            rendererFor = { d ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = d
                    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions) =
                        MigrationDdlResult(statements = emptyList(), operationsRendered = emptySet())
                    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions) =
                        MigrationDdlResult(statements = emptyList(), operationsRendered = emptySet())
                }
            },
            atomicWriter = { p, c -> Files.writeString(p, c) },
            renderReport = { r, _ -> "{\"status\":\"${r.status}\"}" },
            printError = { _, _ -> },
            stdout = { stdout.append(it) },
            stderr = { },
        )
        return runner to stdout
    }

    fun request(dialect: DatabaseDialect) = SchemaMigrateRequest(
        source = sourcePath.toString(),
        target = targetPath.toString(),
        dialect = dialect,
        planOnly = true,
    )

    test("the runner hands the planner Oracle's generation projection, not the identity default") {
        val planner = CapturingPlanner()
        runnerFor(planner).first.execute(request(DatabaseDialect.ORACLE))

        val captured = planner.captured.shouldNotBeNull()
        // Der Default `{ it }` gaebe den Namen unveraendert zurueck -- genau
        // das passiert, wenn eine Aufrufstelle das Argument weglaesst.
        (captured(systemGenerated) as ColumnGeneration.Identity).sequenceName.shouldBeNull()
    }

    test("for PostgreSQL the runner hands through the identity projection") {
        val planner = CapturingPlanner()
        runnerFor(planner).first.execute(request(DatabaseDialect.POSTGRESQL))

        planner.captured.shouldNotBeNull()(systemGenerated) shouldBe systemGenerated
    }

    test("the Oracle endpoint fingerprints carry no system-generated sequence name") {
        val planner = CapturingPlanner()
        val (runner, _) = runnerFor(planner)
        runner.execute(request(DatabaseDialect.ORACLE))

        // Der Abdruck ist ein Hash; die Projektion darunter ist lesbar und
        // ist das, was gehasht wird.
        val projection = dev.dmigrate.core.diff.migration.MigrationFingerprint.project(
            schemaWithIdentity(systemGenerated),
            canonicalizeGeneration = capabilityGenerationCanonicalizer(DatabaseDialect.ORACLE),
        )
        projection shouldNotContain "ISEQ"
        // Und beide Seiten hashen gleich -- ohne den Hook nicht.
        dev.dmigrate.core.diff.migration.MigrationFingerprint.compute(
            schemaWithIdentity(systemGenerated),
            canonicalizeGeneration = capabilityGenerationCanonicalizer(DatabaseDialect.ORACLE),
        ) shouldBe dev.dmigrate.core.diff.migration.MigrationFingerprint.compute(
            schemaWithIdentity(ColumnGeneration.Identity(mode = IdentityMode.ALWAYS, sequenceName = null)),
            canonicalizeGeneration = capabilityGenerationCanonicalizer(DatabaseDialect.ORACLE),
        )
    }
})
