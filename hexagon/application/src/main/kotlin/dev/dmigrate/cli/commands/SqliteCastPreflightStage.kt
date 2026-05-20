package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.CheckPreflightPlanner
import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.MysqlSequenceCanonicityDeclaration
import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
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
    /**
     * E.3 MySQL Sequence Drift-Check Sub-Slice E (2026-05-20):
     * NOT_RUN declarations (one per sequence op, kind=SEQUENCE_ROW)
     * that surface "drift-check skipped because there is no live
     * MySQL target" in the report. Populated by
     * [MigrationPreflightPlanner.plan]; consumed by the renderer via
     * `DdlGenerationOptions.mysqlSequenceCanonicity` when the live
     * probe was not run (file target / non-MySQL dialect /
     * `--plan-only`-style flow).
     */
    val mysqlSequenceCanonicity: List<MysqlSequenceCanonicityDeclaration> = emptyList(),
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
        val mysqlSequencePart = planMysqlSequenceCanonicity(request, target, dialect, plan)
        return MigrationPreflightPlan(
            sqliteCastPreflights = castPart,
            checkPreflights = checkPart,
            mysqlSequenceCanonicity = mysqlSequencePart,
        )
    }

    /**
     * E.3 MySQL Sequence Drift-Check Sub-Slice E (2026-05-20):
     * pre-plans one [MysqlSequenceCanonicityDeclaration] per
     * sequence op in [plan] when the live probe will not run.
     * Returns an empty list when the dialect is not MySQL (the
     * gate is MySQL-only) or when there are no sequence ops at
     * all.
     *
     * Kind is fixed to [MysqlSequenceCanonicityKind.SEQUENCE_ROW]:
     * the row-level declaration is the canonical "this op was on
     * the table" marker. The other kinds (support table, routines,
     * trigger) only need probing when there is a live probe — the
     * NOT_RUN report-line is per-op, not per-canonical-object.
     */
    private fun planMysqlSequenceCanonicity(
        request: SchemaMigrateRequest,
        target: CompareOperand,
        dialect: DatabaseDialect,
        plan: DiffResult,
    ): List<MysqlSequenceCanonicityDeclaration> {
        if (dialect != DatabaseDialect.MYSQL) return emptyList()
        val initialStatus = when {
            request.execute && target is CompareOperand.Database -> MysqlSequenceCanonicityStatus.NOT_RUN_POLICY
            else -> MysqlSequenceCanonicityStatus.NOT_RUN_FILE_TARGET
        }
        return plan.operations.mapNotNull { op ->
            val name = when (op) {
                is DiffOperation.CreateSequence -> op.objectRef.rootName
                is DiffOperation.AlterSequence -> op.objectRef.rootName
                is DiffOperation.DropSequence -> op.objectRef.rootName
                is DiffOperation.RenameSequence -> op.fromName
                else -> return@mapNotNull null
            }
            MysqlSequenceCanonicityDeclaration(
                operationId = op.id,
                dialect = DatabaseDialect.MYSQL.name.lowercase(),
                kind = MysqlSequenceCanonicityKind.SEQUENCE_ROW,
                objectName = name,
                status = initialStatus,
                sqlHash = "not-run",
            )
        }
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
