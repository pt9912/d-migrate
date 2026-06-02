package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.validation.ValidationResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * E.1 Slice F.2: pins that `SchemaMigrateStatementView` carries the
 * scrub-metadata quartet `{sqlHash, sqlLength, scrubbedPreview,
 * scrubbingApplied}` per E.1 Plan §1 Display-Plane defaults, and
 * that the `sql` field is governed by [dev.dmigrate.driver.RoutineBodyDisplay]:
 * scrubbed-only by default, raw under `--debug-body`.
 */
class SchemaMigrateReportBuilderScrubbingTest : FunSpec({

    val schema = SchemaDefinition(name = "App", version = "1")
    val operand = ResolvedSchemaOperand(
        reference = "file:test.yaml",
        schema = schema,
        validation = ValidationResult(),
    )
    val plan = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = emptyList(),
        diagnostics = emptyList(),
    )

    fun rendered(sql: String): MigrationDdlResult = MigrationDdlResult(
        statements = listOf(
            MigrationDdlStatement(
                sql = sql,
                operationIds = setOf("op-1"),
                risk = OperationRisk.SAFE,
                phase = DiffPhase.TABLES,
            ),
        ),
        operationsRendered = setOf("op-1"),
    )

    fun build(rendered: MigrationDdlResult, debugBody: Boolean) =
        SchemaMigrateReportBuilder.build(
            request = SchemaMigrateRequest(
                source = "file:src",
                target = "file:tgt",
                dialect = DatabaseDialect.POSTGRESQL,
                debugBody = debugBody,
            ),
            source = operand,
            target = operand,
            plan = plan,
            rendered = rendered,
            dialect = DatabaseDialect.POSTGRESQL,
            renderedDown = null,
        )

    test("default (SCRUBBED_ONLY) masks credential-shaped literals in sql field") {
        // No trailing ';' so the body round-trips through
        // `RoutineBodyNormalizer.normalise` (which strips trailing
        // semicolons by spec) without changing length.
        val sql = "CREATE FUNCTION login() AS \$\$ password = 'super-secret-123' \$\$ LANGUAGE plpgsql"
        val report = build(rendered(sql), debugBody = false)
        val view = report.statements!!.single()

        // sql field is scrubbed
        view.sql shouldNotContain "super-secret-123"
        view.sql shouldContain "***SCRUBBED***"
        // metadata quartet is populated
        view.sqlHash shouldNotBe ""
        view.sqlLength shouldBe sql.length
        view.scrubbedPreview shouldContain "***SCRUBBED***"
        view.scrubbingApplied shouldBe true
    }

    test("--debug-body (RAW_DEBUG) emits raw sql unchanged") {
        val sql = "CREATE FUNCTION login() AS \$\$ password = 'super-secret-123' \$\$ LANGUAGE plpgsql;"
        val report = build(rendered(sql), debugBody = true)
        val view = report.statements!!.single()

        // sql field is verbatim
        view.sql shouldBe sql
        view.sql shouldContain "super-secret-123"
        // metadata quartet still populated (always-on)
        view.sqlHash shouldNotBe ""
        view.scrubbingApplied shouldBe true
    }

    test("non-secret sql is verbatim and scrubbingApplied=false") {
        // No trailing ';' to keep the normalised form identical to the
        // input (see RoutineBodyNormalizer.normalise contract).
        val sql = "CREATE TABLE orders (id INT)"
        val report = build(rendered(sql), debugBody = false)
        val view = report.statements!!.single()

        view.sql shouldBe sql
        view.scrubbingApplied shouldBe false
        view.scrubbedPreview shouldBe sql
        view.sqlLength shouldBe sql.length
        view.sqlHash shouldNotBe ""
    }

    test("planOnly suppresses statements list entirely") {
        val sql = "CREATE TABLE orders (id INT);"
        val req = SchemaMigrateRequest(
            source = "file:src",
            target = "file:tgt",
            dialect = DatabaseDialect.POSTGRESQL,
            planOnly = true,
        )
        val report = SchemaMigrateReportBuilder.build(
            request = req,
            source = operand,
            target = operand,
            plan = plan,
            rendered = rendered(sql),
            dialect = DatabaseDialect.POSTGRESQL,
            renderedDown = null,
        )
        report.statements shouldBe null
    }

    // ── F.7: executionError redaction is centralised in buildExecutionView ──

    fun renderedWithExecutionError(error: String) = MigrationDdlResult(
        statements = emptyList(),
        operationsRendered = emptySet(),
        executionStarted = true,
        executionCompleted = true,
        executionError = error,
    )

    test("F.7: trace.executionError is redacted by default (executor-returned trace path)") {
        // JdbcMigrationExecutor catches its own SQLException and puts
        // `cause.message` into the trace — this path bypasses F.1's
        // executor-throws redaction. F.7 redacts centrally in the
        // report builder so both paths land here.
        val rawError = "ERROR in CREATE FUNCTION login: password = 'leaky-secret' invalid"
        val report = build(rendered = renderedWithExecutionError(rawError), debugBody = false)
        val execError = report.execution!!.executionError!!

        execError shouldNotContain "leaky-secret"
        execError shouldContain "***SCRUBBED***"
        execError shouldContain "ERROR"
    }

    test("F.7: --debug-body keeps executionError unredacted") {
        val rawError = "ERROR in CREATE FUNCTION login: password = 'leaky-secret' invalid"
        val report = build(rendered = renderedWithExecutionError(rawError), debugBody = true)
        report.execution!!.executionError shouldBe rawError
    }
})
