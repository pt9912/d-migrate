package dev.dmigrate.cli.commands

import com.google.gson.JsonParser
import dev.dmigrate.driver.RoutineBodyDisplay
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class SchemaMigrateReportRendererTest : FunSpec({

    fun parseYaml(out: String): Map<*, *> =
        Load(LoadSettings.builder().build()).loadFromString(out) as Map<*, *>

    fun report(
        statements: List<SchemaMigrateStatementView>? = null,
        execution: SchemaMigrateExecutionView? = null,
        blockers: List<SchemaMigrateBlockerView> = emptyList(),
        diagnostics: List<SchemaMigrateDiagnosticView> = emptyList(),
        sqliteCastPreflights: List<SchemaMigrateSqliteCastPreflightView> = emptyList(),
        bodyDisplay: RoutineBodyDisplay = RoutineBodyDisplay.SCRUBBED_ONLY,
    ) = SchemaMigrateReport(
        status = "ok",
        exitCode = 0,
        source = "file:src.yaml",
        target = "file:tgt.yaml",
        dialect = "POSTGRESQL",
        planOnly = false,
        operations = listOf(
            SchemaMigrateOperationView(
                id = "op-1",
                kind = "CreateTable",
                objectType = "TABLE",
                path = listOf("orders"),
                phase = "TABLES",
                reversibility = "AUTOMATIC_WITH_DATA_RISK",
                rendered = true,
                skipped = false,
            ),
        ),
        statements = statements,
        blockers = blockers,
        diagnostics = diagnostics,
        overlays = listOf(
            SchemaMigrateOverlayView(
                source = "overlays/using.json",
                entryId = "use-email",
                overlayHash = "a".repeat(64),
                diagnosticCode = "OVERLAY_HASH_MISMATCH",
                severity = "BLOCKER",
            ),
        ),
        sqliteCastPreflights = sqliteCastPreflights,
        summary = SchemaMigrateSummary(
            operationsTotal = 1,
            operationsRendered = 1,
            statementsTotal = statements?.size ?: 0,
            spatialProfile = "POSTGIS",
            requiredExtensions = listOf("postgis"),
            missingExtensions = listOf("postgis"),
        ),
        execution = execution,
        bodyDisplay = bodyDisplay,
    )

    test("JSON renderer emits keys for all top-level fields") {
        val out = SchemaMigrateReportRenderer.render(report(), "json")
        out shouldContain "\"status\": \"ok\""
        out shouldContain "\"exitCode\": 0"
        out shouldContain "\"dialect\": \"POSTGRESQL\""
        out shouldContain "\"operations\":"
        out shouldContain "\"materializedViews\":"
        out shouldContain "\"overlays\":"
        out shouldContain "\"sqliteCastPreflights\":"
        out shouldContain "\"summary\":"
        out shouldContain "\"statements\": null"
        out shouldContain "\"requiredExtensions\":[\"postgis\"]"
        out shouldContain "\"missingExtensions\":[\"postgis\"]"
        out shouldContain "\"spatialProfile\":\"POSTGIS\""
        out shouldContain "\"diagnosticCode\":\"OVERLAY_HASH_MISMATCH\""
    }

    test("JSON renderer emits SQLite cast preflight details") {
        val out = SchemaMigrateReportRenderer.render(
            report(
                sqliteCastPreflights = listOf(
                    SchemaMigrateSqliteCastPreflightView(
                        operationId = "op-cast",
                        dialect = "sqlite",
                        table = "users",
                        column = "age",
                        sourceType = "TEXT",
                        targetType = "INTEGER",
                        status = "FAILED",
                        sqlHash = "b".repeat(64),
                        totalRows = 10,
                        failingRows = 2,
                        sampleRowIds = listOf("7", "9"),
                        problem = "not safely convertible",
                    ),
                ),
            ),
            "json",
        )

        out shouldContain "\"sqliteCastPreflights\": ["
        out shouldContain "\"operationId\":\"op-cast\""
        out shouldContain "\"status\":\"FAILED\""
        out shouldContain "\"sqlHash\":\"${"b".repeat(64)}\""
        out shouldContain "\"failingRows\":2"
        out shouldContain "\"sampleRowIds\":[\"7\",\"9\"]"

        val root = JsonParser.parseString(out).asJsonObject
        val preflight = root.getAsJsonArray("sqliteCastPreflights").single().asJsonObject
        preflight["operationId"].asString shouldBe "op-cast"
        preflight["status"].asString shouldBe "FAILED"
        preflight.getAsJsonArray("sampleRowIds").map { it.asString } shouldBe listOf("7", "9")
    }

    test("JSON renderer emits statements when --plan-only is off") {
        val stmts = listOf(
            SchemaMigrateStatementView(
                sql = "CREATE TABLE x (id INT);",
                operationIds = listOf("op-1"),
                phase = "TABLES",
                destructive = false,
            ),
        )
        val out = SchemaMigrateReportRenderer.render(report(statements = stmts), "json")
        out shouldContain "CREATE TABLE x"
    }

    test("JSON renderer emits execution view when present") {
        val exec = SchemaMigrateExecutionView(
            started = true,
            completed = true,
            statementsAttempted = 3,
            lastStatementOperationIds = listOf("op-1", "op-2"),
            transactionRolledBack = false,
            sideEffectsPossible = false,
            executionError = null,
            statementGroups = listOf(
                SchemaMigrateStatementGroupView(
                    statementGroupId = "op-1",
                    operationIds = listOf("op-1"),
                    statementStartInclusive = 0,
                    statementEndExclusive = 2,
                    transactionScope = "RUNNER_OWNED",
                    transactionBoundary = "INSIDE",
                ),
            ),
            recoverability = "FULL_ROLLBACK_CONFIRMED",
        )
        val out = SchemaMigrateReportRenderer.render(report(execution = exec), "json")
        out shouldContain "\"execution\":"
        out shouldContain "\"statementsAttempted\":3"
        out shouldContain "\"recoverability\":\"FULL_ROLLBACK_CONFIRMED\""
        out shouldContain "\"statementGroups\":["
        out shouldContain "\"transactionBoundary\":\"INSIDE\""
    }

    test("JSON renderer emits blockers and diagnostics") {
        val out = SchemaMigrateReportRenderer.render(
            report(
                blockers = listOf(
                    SchemaMigrateBlockerView(
                        reason = "DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION",
                        operationIds = listOf("op-1"),
                        diagnosticCodes = listOf("X1"),
                    ),
                ),
                diagnostics = listOf(
                    SchemaMigrateDiagnosticView(
                        code = "X1",
                        severity = "BLOCKER",
                        message = "drop is destructive",
                        operationId = "op-1",
                    ),
                ),
            ),
            "json",
        )
        out shouldContain "DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION"
        out shouldContain "\"code\":\"X1\""
        out shouldContain "drop is destructive"
    }

    test("JSON renderer emits materialized view refresh staleness contract") {
        val out = SchemaMigrateReportRenderer.render(
            report().copy(
                materializedViews = listOf(
                    SchemaMigrateMaterializedViewContractView(
                        operationId = "view-1",
                        action = "REPLACE",
                        path = listOf("order_summary_mv"),
                        dialect = "POSTGRESQL",
                        status = "BLOCKED_UNTIL_REFRESH_STALENESS_CONTRACT",
                        stalenessAfterUp = "UNKNOWN_BLOCKED",
                        refreshSteps = listOf("BLOCKED_REFRESH_CONTRACT_REQUIRED"),
                        locking = "UNKNOWN_REQUIRES_MANUAL_CONTRACT",
                        rollback = "SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED",
                    ),
                ),
            ),
            "json",
        )

        out shouldContain "\"materializedViews\": ["
        out shouldContain "\"stalenessAfterUp\":\"UNKNOWN_BLOCKED\""
        out shouldContain "\"refreshSteps\":[\"BLOCKED_REFRESH_CONTRACT_REQUIRED\"]"
        out shouldContain "\"locking\":\"UNKNOWN_REQUIRES_MANUAL_CONTRACT\""
        out shouldContain "\"rollback\":\"SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED\""
    }

    test("JSON renderer escapes special characters") {
        val out = SchemaMigrateReportRenderer.render(
            report().copy(
                source = "file:\"src\".yaml",
                target = "line1\nline2\t\u0001",
            ),
            "json",
        )
        out shouldContain "file:\\\"src\\\".yaml"
        out shouldContain "line1\\nline2\\t\\u0001"
    }

    test("YAML renderer emits the canonical keys") {
        val out = SchemaMigrateReportRenderer.render(report(), "yaml")
        out shouldContain "status: ok"
        out shouldContain "dialect: POSTGRESQL"
        out shouldContain "summary:"
        out shouldContain "spatialProfile: POSTGIS"
        out shouldContain "requiredExtensions: [postgis]"
        out shouldContain "overlays:\n  - source: overlays/using.json"
        out shouldContain "sqliteCastPreflights: []"
        out shouldContain "diagnosticCode: OVERLAY_HASH_MISMATCH"
    }

    test("YAML renderer emits SQLite cast preflight details") {
        val out = SchemaMigrateReportRenderer.render(
            report(
                sqliteCastPreflights = listOf(
                    SchemaMigrateSqliteCastPreflightView(
                        operationId = "op-cast",
                        dialect = "sqlite",
                        table = "users",
                        column = "age",
                        sourceType = "TEXT",
                        targetType = "INTEGER",
                        status = "NOT_RUN_FILE_TARGET",
                        sqlHash = "c".repeat(64),
                        totalRows = null,
                        failingRows = null,
                        sampleRowIds = emptyList(),
                        problem = null,
                    ),
                ),
            ),
            "yaml",
        )

        out shouldContain "sqliteCastPreflights:\n  - operationId: op-cast"
        out shouldContain "status: NOT_RUN_FILE_TARGET"
        out shouldContain "sqlHash: ${"c".repeat(64)}"
        out shouldContain "totalRows: null"
    }

    test("YAML renderer quotes and parses SQLite cast preflight free strings") {
        val out = SchemaMigrateReportRenderer.render(
            report(
                sqliteCastPreflights = listOf(
                    SchemaMigrateSqliteCastPreflightView(
                        operationId = "op-cast",
                        dialect = "sqlite",
                        table = "users",
                        column = "age",
                        sourceType = "TEXT",
                        targetType = "INTEGER",
                        status = "FAILED",
                        sqlHash = "e".repeat(64),
                        totalRows = 12,
                        failingRows = 2,
                        sampleRowIds = listOf("row:7", "#9"),
                        problem = "bad: value #1",
                    ),
                ),
            ),
            "yaml",
        )

        val root = parseYaml(out)
        val preflights = root["sqliteCastPreflights"] as List<*>
        val preflight = preflights.single() as Map<*, *>
        preflight["problem"] shouldBe "bad: value #1"
        preflight["sampleRowIds"] shouldBe listOf("row:7", "#9")
    }

    test("YAML renderer quotes strings containing colons") {
        val r = report().copy(target = "db:postgres://localhost:5432")
        val out = SchemaMigrateReportRenderer.render(r, "yaml")
        out shouldContain "\"db:postgres://localhost:5432\""
    }

    test("YAML renderer emits blockers diagnostics and execution") {
        val exec = SchemaMigrateExecutionView(
            started = true,
            completed = false,
            statementsAttempted = 2,
            lastStatementOperationIds = listOf("op-1"),
            transactionRolledBack = true,
            sideEffectsPossible = true,
            executionError = "boom: failed",
            statementGroups = listOf(
                SchemaMigrateStatementGroupView(
                    statementGroupId = "op-1#1",
                    operationIds = listOf("op-1"),
                    statementStartInclusive = 0,
                    statementEndExclusive = 1,
                    transactionScope = "STREAM_OWNED",
                    transactionBoundary = "BEFORE",
                ),
            ),
            recoverability = "ROLLBACK_ATTEMPTED",
        )
        val out = SchemaMigrateReportRenderer.render(
            report(
                execution = exec,
                blockers = listOf(
                    SchemaMigrateBlockerView(
                        reason = "MANUAL_ACTION_REQUIRED",
                        operationIds = listOf("op-1"),
                        diagnosticCodes = listOf("SPATIAL_INDEX_UNSUPPORTED"),
                    ),
                ),
                diagnostics = listOf(
                    SchemaMigrateDiagnosticView(
                        code = "SPATIAL_INDEX_UNSUPPORTED",
                        severity = "BLOCKER",
                        message = "manual spatial index required",
                        operationId = "op-1",
                    ),
                    SchemaMigrateDiagnosticView(
                        code = "NOTE",
                        severity = "INFO",
                        message = "line1\nline2",
                        operationId = null,
                    ),
                ),
            ),
            "yaml",
        )
        out shouldContain "blockers:\n  - reason: MANUAL_ACTION_REQUIRED"
        out shouldContain "diagnosticCodes: [SPATIAL_INDEX_UNSUPPORTED]"
        out shouldContain "diagnostics:\n  - code: SPATIAL_INDEX_UNSUPPORTED"
        out shouldContain "operationId: op-1"
        out shouldContain "message: \"line1\nline2\""
        out shouldContain "execution:\n  started: true"
        out shouldContain "executionError: \"boom: failed\""
        out shouldContain "recoverability: ROLLBACK_ATTEMPTED"
        out shouldContain "statementGroups:\n    - statementGroupId: \"op-1#1\""
        out shouldContain "transactionBoundary: BEFORE"
    }

    test("YAML renderer emits materialized view contract") {
        val out = SchemaMigrateReportRenderer.render(
            report().copy(
                materializedViews = listOf(
                    SchemaMigrateMaterializedViewContractView(
                        operationId = "view-1",
                        action = "DROP",
                        path = listOf("order_summary_mv"),
                        dialect = "POSTGRESQL",
                        status = "BLOCKED_UNTIL_REFRESH_STALENESS_CONTRACT",
                        stalenessAfterUp = "UNKNOWN_BLOCKED",
                        refreshSteps = listOf("BLOCKED_REFRESH_CONTRACT_REQUIRED"),
                        locking = "UNKNOWN_REQUIRES_MANUAL_CONTRACT",
                        rollback = "SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED",
                    ),
                ),
            ),
            "yaml",
        )

        out shouldContain "materializedViews:\n  - operationId: view-1"
        out shouldContain "stalenessAfterUp: UNKNOWN_BLOCKED"
        out shouldContain "refreshSteps: [BLOCKED_REFRESH_CONTRACT_REQUIRED]"
        out shouldContain "locking: UNKNOWN_REQUIRES_MANUAL_CONTRACT"
        out shouldContain "rollback: SOURCE_QUERY_AVAILABLE_REFRESH_CONTRACT_REQUIRED"
    }

    // ── bodyDisplay (E.1 Slice C.1.a) ───────────────────────────────

    test("JSON renderer defaults bodyDisplay to SCRUBBED_ONLY") {
        val out = SchemaMigrateReportRenderer.render(report(), "json")
        out shouldContain "\"bodyDisplay\": \"SCRUBBED_ONLY\""
    }

    test("JSON renderer emits bodyDisplay = RAW_DEBUG when --debug-body was set on the request") {
        val out = SchemaMigrateReportRenderer.render(
            report(bodyDisplay = RoutineBodyDisplay.RAW_DEBUG),
            "json",
        )
        out shouldContain "\"bodyDisplay\": \"RAW_DEBUG\""
    }

    test("YAML renderer emits bodyDisplay") {
        val out = SchemaMigrateReportRenderer.render(
            report(bodyDisplay = RoutineBodyDisplay.RAW_DEBUG),
            "yaml",
        )
        out shouldContain "bodyDisplay: RAW_DEBUG"
    }

    // ── bodyEmbedding (E.1 Slice F.3) ───────────────────────────────

    test("JSON renderer defaults bodyEmbedding to DISABLED with version body-embed.v1") {
        val out = SchemaMigrateReportRenderer.render(report(), "json")
        out shouldContain "\"bodyEmbedding\":"
        out shouldContain "\"status\":\"DISABLED\""
        out shouldContain "\"version\":\"body-embed.v1\""
        out shouldContain "\"source\":\"NONE\""
    }

    test("YAML renderer emits bodyEmbedding block with status / version / source") {
        val out = SchemaMigrateReportRenderer.render(report(), "yaml")
        out shouldContain "bodyEmbedding:"
        out shouldContain "  status: DISABLED"
        out shouldContain "  version: body-embed.v1"
        out shouldContain "  source: NONE"
    }
})
