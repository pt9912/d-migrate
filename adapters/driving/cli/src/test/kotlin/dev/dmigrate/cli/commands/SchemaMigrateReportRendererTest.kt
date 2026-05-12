package dev.dmigrate.cli.commands

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain

class SchemaMigrateReportRendererTest : FunSpec({

    fun report(
        statements: List<SchemaMigrateStatementView>? = null,
        execution: SchemaMigrateExecutionView? = null,
        blockers: List<SchemaMigrateBlockerView> = emptyList(),
        diagnostics: List<SchemaMigrateDiagnosticView> = emptyList(),
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
        summary = SchemaMigrateSummary(
            operationsTotal = 1,
            operationsRendered = 1,
            statementsTotal = statements?.size ?: 0,
            spatialProfile = "POSTGIS",
            requiredExtensions = listOf("postgis"),
            missingExtensions = listOf("postgis"),
        ),
        execution = execution,
    )

    test("JSON renderer emits keys for all top-level fields") {
        val out = SchemaMigrateReportRenderer.render(report(), "json")
        out shouldContain "\"status\": \"ok\""
        out shouldContain "\"exitCode\": 0"
        out shouldContain "\"dialect\": \"POSTGRESQL\""
        out shouldContain "\"operations\":"
        out shouldContain "\"materializedViews\":"
        out shouldContain "\"summary\":"
        out shouldContain "\"statements\": null"
        out shouldContain "\"requiredExtensions\":[\"postgis\"]"
        out shouldContain "\"missingExtensions\":[\"postgis\"]"
        out shouldContain "\"spatialProfile\":\"POSTGIS\""
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
        )
        val out = SchemaMigrateReportRenderer.render(report(execution = exec), "json")
        out shouldContain "\"execution\":"
        out shouldContain "\"statementsAttempted\":3"
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
})
