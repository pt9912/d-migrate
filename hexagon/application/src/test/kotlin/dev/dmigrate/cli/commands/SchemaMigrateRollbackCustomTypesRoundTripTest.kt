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
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.DiffDdlGenerator
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Rundlauf `schema migrate --generate-rollback` -> `schema rollback --execute`
 * gegen ein Schema mit benanntem Typ auf einem Dialekt ohne Custom Types
 * (GitHub-Issue #47). SQLite kann `custom_types` nicht zurueckgeben: das
 * zurueckgelesene Ziel traegt keine, das Artefakt haelt aber den Abdruck des
 * Soll-Schemas mit.
 */
class SchemaMigrateRollbackCustomTypesRoundTripTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("migrate-rollback-custom-types")
    val sourcePath = tmpDir.resolve("source.yaml").also { Files.writeString(it, "# source") }

    val columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true))
    val enumType = mapOf("ee" to CustomTypeDefinition(kind = CustomTypeKind.ENUM, values = listOf("a", "b")))

    fun schema(
        customTypes: Map<String, CustomTypeDefinition>,
        withExtra: Boolean,
        enumColumn: ColumnDefinition? = null,
    ) = SchemaDefinition(
        name = "App", version = "1", customTypes = customTypes,
        tables = mapOf("t" to TableDefinition(
            columns = columns +
                (if (withExtra) mapOf("extra" to ColumnDefinition(NeutralType.Text())) else emptyMap()) +
                (enumColumn?.let { mapOf("e" to it) } ?: emptyMap()),
            primaryKey = listOf("id"),
        )),
    )

    // Soll: mit benanntem Typ. Zustand der SQLite-Datenbank: ohne, sie kennt keinen.
    var authored = schema(enumType, withExtra = true)
    var readBack = schema(emptyMap(), withExtra = true)
    var before = schema(emptyMap(), withExtra = false)

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

    fun migrate(execute: Boolean, name: String): Map<Path, String> {
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
                    dialect = DatabaseDialect.SQLITE,
                )
            },
            comparator = { a, b -> SchemaComparator().compare(a, b) },
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
            clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC),
        )
        val exit = runner.execute(
            SchemaMigrateRequest(
                source = sourcePath.toString(),
                target = "db:sqlite://test.db",
                dialect = DatabaseDialect.SQLITE,
                execute = execute,
                generateRollback = true,
                rollbackOutput = tmpDir.resolve("$name-down.sql"),
                report = tmpDir.resolve("$name-report.json"),
            ),
        )
        check(written.containsKey(tmpDir.resolve("$name-down.sql"))) {
            "migrate exit=$exit, written=${written.keys.map { it.fileName }}, errors=$errors"
        }
        return written
    }

    fun rollback(artefact: String, target: SchemaDefinition, name: String): Int {
        val path = tmpDir.resolve("$name-rollback-input.sql").also { Files.writeString(it, artefact) }
        return SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand("db:${op.source}", target, ValidationResult(), dialect = DatabaseDialect.SQLITE)
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            printError = { _, _ -> },
        ).execute(SchemaRollbackRequest(source = path, target = "db:sqlite://test.db", execute = true))
    }

    test("bestaetigter Post-Compare: das Artefakt besteht den Abgleich gegen die Datenbank ohne benannte Typen") {
        val artefact = migrate(execute = true, name = "clean").getValue(tmpDir.resolve("clean-down.sql"))
        rollback(artefact, readBack, "clean") shouldBe 0
    }

    test("ohne --execute: das geplante Artefakt besteht den Abgleich, sobald das Up angewandt ist") {
        val artefact = migrate(execute = false, name = "plan").getValue(tmpDir.resolve("plan-down.sql"))
        rollback(artefact, readBack, "plan") shouldBe 0
    }
})
