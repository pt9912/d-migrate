package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaComparator
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
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
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Rundlauf `schema migrate --generate-rollback` -> `schema rollback --execute`
 * gegen ein Schema mit rohem CHECK-Text (GitHub-Issue #46). Das Ziel gibt den
 * Text in der Katalogform des Servers zurueck, nie in der Autorenschreibweise.
 *
 * Deckt jeden Weg ab, auf dem ein Artefakt seinen erwarteten Zustand erhaelt:
 * den bestaetigten Post-Compare, den Lauf ohne `--execute` (kein Post-Compare)
 * und das Recovery-Artefakt nach gescheiterter Introspektion.
 */
class SchemaMigrateRollbackRawSqlRoundTripTest : FunSpec({

    val tmpDir: Path = Files.createTempDirectory("migrate-rollback-raw-sql")
    val sourcePath = tmpDir.resolve("source.yaml").also { Files.writeString(it, "# source") }

    fun table(check: String?) = TableDefinition(
        columns = mapOf("id" to ColumnDefinition(NeutralType.Integer, required = true)),
        primaryKey = listOf("id"),
        constraints = check?.let { listOf(ConstraintDefinition("chk_t", ConstraintType.CHECK, expression = it)) }
            ?: emptyList(),
    )

    fun schema(check: String?) = SchemaDefinition(name = "App", version = "1", tables = mapOf("t" to table(check)))

    val authored = schema("id >= 1")
    val served = schema("((id >= 1))")
    val before = schema(null)

    fun rendered() = MigrationDdlResult(
        statements = listOf(
            MigrationDdlStatement(
                sql = "ALTER TABLE t DROP CONSTRAINT chk_t;",
                operationIds = setOf("op-1"),
                risk = OperationRisk.SAFE,
                phase = DiffPhase.TABLES,
            ),
        ),
        operationsRendered = setOf("op-1"),
    )

    /** Fuehrt migrate aus und liefert das geschriebene Artefakt (Pfad -> Text). */
    fun migrate(
        execute: Boolean,
        postState: () -> SchemaDefinition,
        name: String,
    ): Pair<Int, Map<Path, String>> {
        val written = mutableMapOf<Path, String>()
        var loads = 0
        val runner = SchemaMigrateRunner(
            fileLoader = { ResolvedSchemaOperand("file:src", authored, ValidationResult()) },
            dbLoader = { op, _ ->
                loads++
                ResolvedSchemaOperand(
                    reference = "db:${op.source}",
                    schema = if (loads == 1) before else postState(),
                    validation = ValidationResult(),
                    dialect = DatabaseDialect.POSTGRESQL,
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
            printError = { _, _ -> },
            clock = Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC),
        )
        val exit = runner.execute(
            SchemaMigrateRequest(
                source = sourcePath.toString(),
                target = "db:postgres://localhost/test",
                dialect = DatabaseDialect.POSTGRESQL,
                execute = execute,
                generateRollback = true,
                rollbackOutput = tmpDir.resolve("$name-down.sql"),
                report = tmpDir.resolve("$name-report.json"),
            ),
        )
        return exit to written
    }

    /** `schema rollback --execute` des Artefakts gegen ein Ziel im Zustand [target]. */
    fun rollback(artefact: String, target: SchemaDefinition, name: String): Int {
        val path = tmpDir.resolve("$name-rollback-input.sql").also { Files.writeString(it, artefact) }
        return SchemaRollbackRunner(
            dbLoader = { op, _ ->
                ResolvedSchemaOperand("db:${op.source}", target, ValidationResult(), dialect = DatabaseDialect.POSTGRESQL)
            },
            executor = { _, _, _ -> ExecutionTrace(executionStarted = true, executionCompleted = true) },
            printError = { _, _ -> },
        ).execute(SchemaRollbackRequest(source = path, target = "db:postgres://localhost", execute = true))
    }

    test("bestaetigter Post-Compare: das Artefakt besteht den Abgleich gegen die Katalogform") {
        val (exit, written) = migrate(execute = true, postState = { served }, name = "clean")
        exit shouldBe 0
        val artefact = written.getValue(tmpDir.resolve("clean-down.sql"))
        artefact shouldContain "\"postUpVerified\":true"
        rollback(artefact, served, "clean") shouldBe 0
    }

    test("ohne --execute: das geplante Artefakt besteht den Abgleich, sobald das Up angewandt ist") {
        val (exit, written) = migrate(execute = false, postState = { before }, name = "plan")
        exit shouldBe 0
        val artefact = written.getValue(tmpDir.resolve("plan-down.sql"))
        artefact shouldContain "\"postUpVerified\":false"
        rollback(artefact, served, "plan") shouldBe 0
    }

    test("Recovery nach gescheiterter Introspektion: das Artefakt besteht den Abgleich") {
        val (exit, written) = migrate(execute = true, postState = { error("simulated introspection failure") }, name = "recovery")
        exit shouldBe 5
        val artefact = written.entries.single { it.key.fileName.toString().contains(".recovery.") }.value
        artefact shouldContain "\"recovery\":true"
        rollback(artefact, served, "recovery") shouldBe 0
    }

    test("ein anderer Zustand bleibt TARGET_STATE_MISMATCH, auch mit rohem Text im Schema") {
        val (_, written) = migrate(execute = true, postState = { served }, name = "drift")
        val artefact = written.getValue(tmpDir.resolve("drift-down.sql"))
        val drifted = served.copy(
            tables = mapOf("t" to table("id >= 1").copy(
                columns = table("id >= 1").columns + ("extra" to ColumnDefinition(NeutralType.Integer)),
            )),
        )
        rollback(artefact, drifted, "drift") shouldBe 8
    }
})
