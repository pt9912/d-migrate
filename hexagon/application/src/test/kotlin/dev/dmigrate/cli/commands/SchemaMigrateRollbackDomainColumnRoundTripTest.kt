package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Rundlauf `schema migrate --execute --generate-rollback` -> `schema rollback --execute` gegen PostgreSQL mit einer
 * Domain-Spalte (Issue #68). Der Reader liefert die Spalte als `Enum(refType)`; das Artefakt haelt den beobachteten
 * Abdruck der Datenbank (schemagebunden gefaltet), der Rollback bildet ihn fuer das gelesene Ziel neu. Hasht der
 * Rollback kontextfrei, steht dort `enum(ref:dd)` gegen `integer` im Artefakt: `TARGET_STATE_MISMATCH`.
 */
class SchemaMigrateRollbackDomainColumnRoundTripTest : FunSpec({

    beforeSpec { DatabaseDriverRegistry.loadAll() }
    afterSpec { DatabaseDriverRegistry.clear() }

    val tmpDir: Path = Files.createTempDirectory("migrate-rollback-domain-column")
    val sourcePath = tmpDir.resolve("source.yaml").also { Files.writeString(it, "# source") }

    val domain = mapOf("dd" to CustomTypeDefinition(kind = CustomTypeKind.DOMAIN, baseType = "integer", check = "VALUE >= 0"))
    val ref = NeutralType.Enum(refType = "dd")

    fun schema(domainColumn: NeutralType, withExtra: Boolean) = SchemaDefinition(
        name = "App", version = "1", customTypes = domain,
        tables = mapOf(
            "t" to TableDefinition(
                columns = mapOf(
                    "id" to ColumnDefinition(NeutralType.Integer, required = true),
                    "d" to ColumnDefinition(domainColumn),
                ) + (if (withExtra) mapOf("extra" to ColumnDefinition(NeutralType.Text())) else emptyMap()),
                primaryKey = listOf("id"),
            ),
        ),
    )

    // Die Datenbank vor dem Lauf und danach: der Reader nennt die Domain, mit und ohne die zusaetzliche Spalte.
    val before = schema(ref, withExtra = false)
    val readBack = schema(ref, withExtra = true)

    fun rendered() = MigrationDdlResult(
        statements = listOf(
            MigrationDdlStatement(
                sql = "ALTER TABLE t DROP COLUMN extra;",
                operationIds = setOf("op-1"),
                risk = OperationRisk.SAFE,
                phase = DiffPhase.TABLES,
            ),
        ),
        operationsRendered = setOf("op-1"),
    )

    data class Run(val exit: Int, val artefact: String?, val errors: List<String>)

    fun migrate(authored: SchemaDefinition, name: String): Run {
        val written = mutableMapOf<Path, String>()
        var loads = 0
        val errors = mutableListOf<String>()
        val runner = SchemaMigrateRunner(
            fileLoader = { ResolvedSchemaOperand("file:src", authored, ValidationResult()) },
            dbLoader = { op, _ ->
                loads++
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = if (loads == 1) before else readBack,
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
            targetAwareComparator = { l, r, projection, authorship, serverForm ->
                SchemaComparator(projection, authorship, serverForm).compare(l, r)
            },
            rendererFor = { dialect ->
                object : DiffDdlGenerator {
                    override val dialect: DatabaseDialect = dialect
                    override fun generateUp(diff: DiffResult, options: DdlGenerationOptions) = rendered()
                    override fun generateDown(diff: DiffResult, options: DdlGenerationOptions) = rendered()
                }
            },
            executor = { _, _, _, _, _ ->
                ExecutionTrace(executionStarted = true, executionCompleted = true, statementsAttempted = 1)
            },
            atomicWriter = { p, c -> written[p] = c; Files.writeString(p, c) },
            renderReport = { _, _ -> "{}" },
            printError = { m, _ -> errors += m },
            clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC),
        )
        val down = tmpDir.resolve("$name-down.sql")
        val exit = runner.execute(
            SchemaMigrateRequest(
                source = sourcePath.toString(),
                target = "db:postgresql://test/db",
                dialect = DatabaseDialect.POSTGRESQL,
                execute = true,
                generateRollback = true,
                rollbackOutput = down,
                report = tmpDir.resolve("$name-report.json"),
            ),
        )
        return Run(exit, written[down], errors)
    }

    fun rollback(artefact: String, target: SchemaDefinition, name: String): Int {
        val path = tmpDir.resolve("$name-rollback-input.sql").also { Files.writeString(it, artefact) }
        return SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand("db:${op.source}", target, ValidationResult(), dialect = DatabaseDialect.POSTGRESQL)
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            printError = { _, _ -> },
        ).execute(SchemaRollbackRequest(source = path, target = "db:postgresql://test/db", execute = true))
    }

    test("Datei mit ref_type: Migrate ohne Drift, das Artefakt entsteht, der Rollback trifft den Abdruck des Ziels") {
        val run = migrate(schema(ref, withExtra = true), "ref")

        withClue(run.errors.toString()) { run.exit shouldBe 0 }
        rollback(run.artefact!!, readBack, "ref") shouldBe 0
    }

    test("aeltere Datei mit Basistyp: Migrate ohne Drift, das Artefakt entsteht, der Rollback trifft den Abdruck") {
        val run = migrate(schema(NeutralType.Integer, withExtra = true), "alt")

        withClue(run.errors.toString()) { run.exit shouldBe 0 }
        rollback(run.artefact!!, readBack, "alt") shouldBe 0
    }

    test("ein Ziel mit anderem Zustand scheitert weiter an TARGET_STATE_MISMATCH") {
        val run = migrate(schema(ref, withExtra = true), "mismatch")

        rollback(run.artefact!!, before, "mismatch") shouldBe 8
    }
})
