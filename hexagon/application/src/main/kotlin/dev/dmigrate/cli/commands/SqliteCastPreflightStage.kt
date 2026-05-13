package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.SqliteCastPreflightStatus
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import java.nio.file.Path

typealias SqliteCastPreflightProbeFn =
    (CompareOperand.Database, Path?, DiffResult) -> List<SqliteCastPreflightDeclaration>

typealias SqliteCastPreflightPlannerFn =
    (DiffResult, SqliteCastPreflightStatus, String?) -> List<SqliteCastPreflightDeclaration>

data class MigrationPreflightPlan(
    val sqliteCastPreflights: List<SqliteCastPreflightDeclaration> = emptyList(),
) {
    companion object {
        val EMPTY: MigrationPreflightPlan = MigrationPreflightPlan()
    }
}

object MigrationPreflightPlanner {

    fun plan(
        sqliteCastPlanner: SqliteCastPreflightPlannerFn?,
        request: SchemaMigrateRequest,
        target: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
    ): MigrationPreflightPlan {
        if (dialect != DatabaseDialect.SQLITE) return MigrationPreflightPlan.EMPTY
        val planner = sqliteCastPlanner ?: return MigrationPreflightPlan.EMPTY
        val status = when {
            request.execute && target is CompareOperand.Database -> SqliteCastPreflightStatus.NOT_RUN_POLICY
            else -> SqliteCastPreflightStatus.NOT_RUN_FILE_TARGET
        }
        return MigrationPreflightPlan(
            sqliteCastPreflights = planner(plan, status, null),
        )
    }
}

object SqliteCastPreflightStage {

    sealed interface Outcome {
        data class Succeeded(val declarations: List<SqliteCastPreflightDeclaration>) : Outcome
        data class Failed(
            val message: String,
            val declarations: List<SqliteCastPreflightDeclaration>,
        ) : Outcome
        data object NotRun : Outcome
    }

    fun run(
        probe: SqliteCastPreflightProbeFn?,
        planner: SqliteCastPreflightPlannerFn?,
        request: SchemaMigrateRequest,
        target: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
        preflightPlan: MigrationPreflightPlan = MigrationPreflightPlanner.plan(planner, request, target, dialect, plan),
    ): Outcome {
        if (dialect != DatabaseDialect.SQLITE) return Outcome.NotRun
        if (!request.execute) return Outcome.NotRun
        val dbTarget = target as? CompareOperand.Database ?: return Outcome.NotRun
        if (probe == null) return Outcome.NotRun
        return try {
            Outcome.Succeeded(probe(dbTarget, request.cliConfigPath, plan))
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName.orEmpty()
            Outcome.Failed(
                message = message,
                declarations = preflightPlan.sqliteCastPreflights.map { declaration ->
                    declaration.copy(problem = "SQLite cast preflight failed before render/execute: $message")
                },
            )
        }
    }

    fun buildFailureResult(
        message: String,
        declarations: List<SqliteCastPreflightDeclaration> = emptyList(),
    ): MigrationDdlResult {
        val diagnostic = DiffDiagnostic(
            code = "SQLITE_CAST_PREFLIGHT_RUN_FAILED",
            message = "SQLite cast preflight failed before render/execute: $message",
            severity = DiffDiagnostic.Severity.BLOCKER,
        )
        return MigrationDdlResult(
            statements = emptyList(),
            operationsRendered = emptySet(),
            blockers = listOf(
                MigrationBlocker(
                    reason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
                    diagnostics = listOf(diagnostic),
                ),
            ),
            primaryBlockedReason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            diagnostics = listOf(diagnostic),
            sqliteCastPreflights = declarations,
        )
    }
}
