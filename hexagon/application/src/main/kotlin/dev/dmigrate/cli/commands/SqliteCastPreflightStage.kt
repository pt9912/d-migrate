package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import java.nio.file.Path

typealias SqliteCastPreflightProbeFn =
    (CompareOperand.Database, Path?, DiffResult) -> List<SqliteCastPreflightDeclaration>

typealias SqliteCastPreflightPlannerFn =
    (DiffResult) -> List<SqliteCastPreflightDeclaration>

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
                declarations = planner?.invoke(plan)
                    ?.map { declaration ->
                        declaration.copy(problem = "SQLite cast preflight failed before render/execute: $message")
                    }
                    .orEmpty(),
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
