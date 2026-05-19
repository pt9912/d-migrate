package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.CheckPreflightPlanner
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
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

/**
 * F.5 Sub-Slice E.4: cross-dialect CHECK live-data preflight probe.
 * Maps a database target to the per-dialect probe implementation in
 * the corresponding driven adapter (PG / MySQL / SQLite). The
 * dialect is resolved at call time so the application layer doesn't
 * carry direct adapter dependencies.
 */
typealias CheckPreflightProbeFn =
    (CompareOperand.Database, Path?, DiffResult, DatabaseDialect) -> List<CheckPreflightDeclaration>

data class MigrationPreflightPlan(
    val sqliteCastPreflights: List<SqliteCastPreflightDeclaration> = emptyList(),
    /**
     * F.5 Sub-Slice E.4: NOT_RUN declarations for every
     * `AddConstraint(CHECK)` operation in the plan. Populated by
     * [MigrationPreflightPlanner.plan]; consumed by the renderer via
     * `DdlGenerationOptions.checkPreflights` when the live probe was
     * not run (file target or `NOT_RUN_POLICY`).
     */
    val checkPreflights: List<CheckPreflightDeclaration> = emptyList(),
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
        val castPart = if (dialect == DatabaseDialect.SQLITE) {
            sqliteCastPlanner?.let { planner ->
                val status = when {
                    request.execute && target is CompareOperand.Database -> SqliteCastPreflightStatus.NOT_RUN_POLICY
                    else -> SqliteCastPreflightStatus.NOT_RUN_FILE_TARGET
                }
                planner(plan, status, null)
            } ?: emptyList()
        } else {
            emptyList()
        }
        val checkPart = planCheckPreflights(request, target, dialect, plan)
        return MigrationPreflightPlan(
            sqliteCastPreflights = castPart,
            checkPreflights = checkPart,
        )
    }

    /**
     * Cross-dialect CHECK preflight pre-planning. Produces one
     * `NOT_RUN_FILE_TARGET` / `NOT_RUN_POLICY` declaration per
     * `AddConstraint(CHECK)` op in [plan] so the report always
     * surfaces what *would* be probed even when no live target is
     * reachable. Uses `CheckPreflightPlanner` from `hexagon:core` for
     * the actual scan and SHA-256 stamping.
     */
    private fun planCheckPreflights(
        request: SchemaMigrateRequest,
        target: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
    ): List<CheckPreflightDeclaration> {
        val initialStatus = when {
            request.execute && target is CompareOperand.Database -> CheckPreflightStatus.NOT_RUN_POLICY
            else -> CheckPreflightStatus.NOT_RUN_FILE_TARGET
        }
        val plannerInitial = when (initialStatus) {
            CheckPreflightStatus.NOT_RUN_POLICY -> CheckPreflightPlanner.InitialStatus.NOT_RUN_POLICY
            else -> CheckPreflightPlanner.InitialStatus.NOT_RUN_FILE_TARGET
        }
        return CheckPreflightPlanner.plan(
            diff = plan,
            dialect = dialect.name.lowercase(),
            initialStatus = plannerInitial,
            identifierQuoter = { SqlIdentifiers.quoteIdentifier(it, dialect) },
        ).map { planned ->
            CheckPreflightDeclaration(
                operationId = planned.operationId,
                dialect = planned.dialect,
                table = planned.table,
                constraintName = planned.constraintName,
                expression = planned.expression,
                status = initialStatus,
                sqlHash = planned.sqlHash,
            )
        }
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

/**
 * F.5 Sub-Slice E.4: cross-dialect counterpart to
 * [SqliteCastPreflightStage]. Runs the live CHECK preflight probe
 * (PG / MySQL / SQLite, chosen by the supplied [CheckPreflightProbeFn])
 * when the request is `--execute` against a database target. Surfaces
 * the same three-way [Outcome] shape so [SchemaMigrateRenderPipeline]
 * can short-circuit before render on a probe technical failure.
 *
 * The probe runs only when:
 *
 * - the request has `execute = true` (file-only paths leave the
 *   pre-planned `NOT_RUN_*` declarations untouched);
 * - the target is a [CompareOperand.Database];
 * - a [CheckPreflightProbeFn] was wired by the CLI;
 * - the pre-planned declaration list is non-empty (no CHECK Adds in
 *   the diff → nothing to probe).
 *
 * Otherwise the stage returns [Outcome.NotRun] and the renderer sees
 * the planner-emitted `NOT_RUN_FILE_TARGET` / `NOT_RUN_POLICY`
 * declarations from [MigrationPreflightPlan.checkPreflights].
 */
object CheckPreflightStage {

    sealed interface Outcome {
        data class Succeeded(val declarations: List<CheckPreflightDeclaration>) : Outcome
        data class Failed(
            val message: String,
            val declarations: List<CheckPreflightDeclaration>,
        ) : Outcome
        data object NotRun : Outcome
    }

    fun run(
        probe: CheckPreflightProbeFn?,
        request: SchemaMigrateRequest,
        target: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
        preflightPlan: MigrationPreflightPlan,
    ): Outcome {
        if (!request.execute) return Outcome.NotRun
        val dbTarget = target as? CompareOperand.Database ?: return Outcome.NotRun
        if (probe == null) return Outcome.NotRun
        if (preflightPlan.checkPreflights.isEmpty()) return Outcome.NotRun
        return try {
            Outcome.Succeeded(probe(dbTarget, request.cliConfigPath, plan, dialect))
        } catch (e: Exception) {
            val message = e.message ?: e::class.simpleName.orEmpty()
            Outcome.Failed(
                message = message,
                declarations = preflightPlan.checkPreflights.map { declaration ->
                    declaration.copy(
                        status = CheckPreflightStatus.PROBE_RUNTIME_ERROR,
                        problem = "CHECK preflight failed before render/execute: $message",
                    )
                },
            )
        }
    }

    fun buildFailureResult(
        message: String,
        declarations: List<CheckPreflightDeclaration> = emptyList(),
    ): MigrationDdlResult {
        val diagnostic = DiffDiagnostic(
            code = "CHECK_PREFLIGHT_RUN_FAILED",
            message = "CHECK preflight failed before render/execute: $message",
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
            checkPreflights = declarations,
        )
    }
}
