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
        ),
        execution = execution,
    )

    test("JSON renderer emits keys for all top-level fields") {
        val out = SchemaMigrateReportRenderer.render(report(), "json")
        out shouldContain "\"status\": \"ok\""
        out shouldContain "\"exitCode\": 0"
        out shouldContain "\"dialect\": \"POSTGRESQL\""
        out shouldContain "\"operations\":"
        out shouldContain "\"summary\":"
        out shouldContain "\"statements\": null"
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

    test("YAML renderer emits the canonical keys") {
        val out = SchemaMigrateReportRenderer.render(report(), "yaml")
        out shouldContain "status: ok"
        out shouldContain "dialect: POSTGRESQL"
        out shouldContain "summary:"
    }

    test("YAML renderer quotes strings containing colons") {
        val r = report().copy(target = "db:postgres://localhost:5432")
        val out = SchemaMigrateReportRenderer.render(r, "yaml")
        out shouldContain "\"db:postgres://localhost:5432\""
    }
})
