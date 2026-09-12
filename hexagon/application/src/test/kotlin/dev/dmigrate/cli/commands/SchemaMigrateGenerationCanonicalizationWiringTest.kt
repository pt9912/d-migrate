package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.TargetProjection
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
        var capturedPartitioning: ((dev.dmigrate.core.model.PartitionConfig) ->
        dev.dmigrate.core.model.PartitionConfig)? = null

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
            canonicalizePartitioning: (dev.dmigrate.core.model.PartitionConfig) ->
            dev.dmigrate.core.model.PartitionConfig,
            foldsAutoIncrementOntoIdentity: Boolean,
        ): DiffResult {
            captured = canonicalizeGeneration
            capturedPartitioning = canonicalizePartitioning
            return super.plan(
                current, desired, schemaDiff, migrationOverlays, capabilities, triggerPlanningContext,
                canonicalizeType, canonicalizeIndex, canonicalizeGeneration, canonicalizePartitioning,
                foldsAutoIncrementOntoIdentity,
            )
        }
    }

    /** Was der Runner dem ziel-bewussten Comparator mitgibt. */
    var capturedProjection: TargetProjection? = null

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
            targetAwareComparator = { a, b, projection, authorship, serverForm ->
                capturedProjection = projection
                SchemaComparator(projection, authorship, serverForm).compare(a, b)
            },
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

    // ── `--target-version` ────────────────────────────────────────
    //
    // Ob es eine virtuelle berechnete Spalte gibt, haengt bei PostgreSQL an
    // der Version: bis 17 nicht, ab 18 doch. Der Lauf ist hier Datei-zu-Datei,
    // es gibt also keine gelesene Version — die Angabe ist das Einzige, was
    // die Faehigkeit entscheidet, und sie muss bis zum Planer durchkommen.

    test("an explicit target version decides which capabilities the planner is handed") {
        val virtual = ColumnGeneration.Computed("a * b", stored = false)

        val below = CapturingPlanner()
        runnerFor(below).first.execute(
            request(DatabaseDialect.POSTGRESQL).copy(targetVersion = "16"),
        )
        val above = CapturingPlanner()
        runnerFor(above).first.execute(
            request(DatabaseDialect.POSTGRESQL).copy(targetVersion = "18"),
        )

        // Unter 18 gibt es die virtuelle Form nicht — `stored` ist dort keine
        // Wahl, und der Vergleich faltet sie weg.
        (below.captured.shouldNotBeNull()(virtual) as ColumnGeneration.Computed).stored shouldBe true
        // Ab 18 ist sie eine Wahl und bleibt stehen.
        (above.captured.shouldNotBeNull()(virtual) as ColumnGeneration.Computed).stored shouldBe false
    }

    test("the runner hands the planner Oracle's generation projection, not the identity default") {
        val planner = CapturingPlanner()
        runnerFor(planner).first.execute(request(DatabaseDialect.ORACLE))

        val captured = planner.captured.shouldNotBeNull()
        // Der Default `{ it }` gaebe den Namen unveraendert zurueck -- genau
        // das passiert, wenn eine Aufrufstelle das Argument weglaesst.
        (captured(systemGenerated) as ColumnGeneration.Identity).sequenceName.shouldBeNull()
    }

    test("the runner hands the planner Oracle's partition projection, not the identity default") {
        // Der Planner rechnet die Abdruecke, die ins ARTEFAKT gehen. Bekaeme
        // er die Projektion nicht, verglichen Artefakt und Post-Compare zwei
        // verschieden projizierte Abdruecke -- und `schema rollback` scheiterte
        // an einem korrekten Ziel.
        val planner = CapturingPlanner()
        runnerFor(planner).first.execute(request(DatabaseDialect.ORACLE))

        val captured = planner.capturedPartitioning.shouldNotBeNull()
        val withLowerBound = dev.dmigrate.core.model.PartitionConfig(
            type = dev.dmigrate.core.model.PartitionType.RANGE,
            key = listOf("d"),
            partitions = listOf(
                dev.dmigrate.core.model.PartitionDefinition(
                    name = "p1",
                    from = listOf(dev.dmigrate.core.model.PartitionBound.MinValue),
                    to = listOf(dev.dmigrate.core.model.PartitionBound.MaxValue),
                ),
            ),
        )
        captured(withLowerBound).partitions.single().from.shouldBeNull()
    }

    test("PostgreSQL folds the identity sequence name away as well") {
        val planner = CapturingPlanner()
        runnerFor(planner).first.execute(request(DatabaseDialect.POSTGRESQL))

        // Der PG-Reverse liest den Namen schema-qualifiziert; ein Soll-Schema
        // kann ihn nicht tragen, auch nicht unqualifiziert von Hand.
        val projected = planner.captured.shouldNotBeNull()(systemGenerated)
        (projected as ColumnGeneration.Identity).sequenceName.shouldBeNull()
    }

    test("a dialect whose reverse never reads the name keeps it untouched") {
        val planner = CapturingPlanner()
        runnerFor(planner).first.execute(request(DatabaseDialect.MYSQL))

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

    /**
     * Dieselbe Erwaegung wie oben, eine Naht weiter: `TargetProjection`
     * traegt fuer drei seiner vier Felder den Default `{ it }`. Wer beim
     * Bauen des Buendels eines weglaesst, kompiliert — und ein Test, der nur
     * den Comparator mit handgeschriebenen Lambdas prueft, bliebe gruen.
     *
     * Hier zaehlt deshalb, was der Runner **tatsaechlich** uebergibt.
     */
    test("the runner builds the comparator projection from the dialect capabilities, not from defaults") {
        capturedProjection = null
        runnerFor(CapturingPlanner()).first.execute(request(DatabaseDialect.ORACLE))

        val projection = capturedProjection.shouldNotBeNull()

        // Oracle liest den system-vergebenen Sequenznamen zurueck, ein Soll
        // kann ihn nicht tragen.
        (projection.generation(systemGenerated) as ColumnGeneration.Identity).sequenceName.shouldBeNull()

        // Oracle legt keine Bitmap-Zugriffsart ab, die es nicht kann, und
        // fuehrt die Text-Search-Konfiguration nicht.
        projection.index(
            IndexDefinition(
                name = "ft",
                columns = listOf(dev.dmigrate.core.model.IndexColumn("body")),
                type = dev.dmigrate.core.model.IndexType.FULLTEXT,
                textSearchConfig = "english",
            ),
        ).textSearchConfig.shouldBeNull()

        // Und Oracle meldet weder Modulus noch Remainder einer HASH-Partition.
        projection.partitioning(
            dev.dmigrate.core.model.PartitionConfig(
                type = dev.dmigrate.core.model.PartitionType.HASH,
                key = listOf("id"),
                partitions = listOf(
                    dev.dmigrate.core.model.PartitionDefinition(name = "p0", modulus = 4, remainder = 0),
                ),
            ),
        ).partitions.single().modulus.shouldBeNull()
    }
})
