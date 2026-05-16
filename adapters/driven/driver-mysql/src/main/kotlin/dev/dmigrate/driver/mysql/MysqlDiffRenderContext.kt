package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.DialectExecutionHints
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionBehavior
import dev.dmigrate.driver.migration.TransactionScope

/** Rendering direction. */
internal enum class MysqlRenderDirection { UP, DOWN }

/** Mutable accumulator for one MySQL renderer invocation. Mirrors `PostgresDiffRenderContext`. */
internal class MysqlDiffRenderContext(
    val direction: MysqlRenderDirection,
    val sql: MysqlDiffSqlBuilders,
    val options: DdlGenerationOptions,
    private val currentSchema: SchemaDefinition? = null,
    private val desiredSchema: SchemaDefinition? = null,
    /**
     * E.1 Routine-Migration Slice C.3: the full plan is now exposed
     * to the renderer so [MysqlDiffRoutineOps] can ask
     * [dev.dmigrate.driver.DependencyGuardEvaluator] whether a
     * routine operation is isolated enough to allow a
     * `DROP + CREATE` fallback when capability is `Disabled`.
     */
    val plan: DiffResult? = null,
) {
    private val statements = mutableListOf<MigrationDdlStatement>()
    private val rendered = mutableSetOf<String>()
    private val skipped = mutableSetOf<String>()
    private val manualActions = mutableSetOf<String>()
    private val destructive = mutableSetOf<String>()
    private val nonReversible = mutableSetOf<String>()
    private val blockers = mutableListOf<MigrationBlocker>()
    private val diagnostics = mutableListOf<DiffDiagnostic>()

    fun emit(op: DiffOperation, sqlText: String) {
        // Plan-2 §G.1: MySQL DDL renders inside the runner-managed JDBC
        // transaction at the dispatch layer (TransactionScope.RUNNER_OWNED),
        // but Plan-2 §A.1 records the dialect-level caveat: every
        // MySQL DDL implicitly commits surrounding work and is not
        // rolled back on later failure, so the hints carry
        // IMPLICIT_COMMIT + sideEffectsPossible=true. The implicit-
        // commit caveat is surfaced in the migrate report's plan-level
        // aggregation; it does not change executor dispatch.
        // Online vs. copy ALTER cannot be determined offline, so
        // requiresExclusiveAccess stays conservatively true and
        // lockBehavior reports TABLE_EXCLUSIVE.
        statements += MigrationDdlStatement(
            sql = sqlText,
            operationIds = setOf(op.id),
            risk = riskFor(op),
            phase = op.phase,
            transactionScope = TransactionScope.RUNNER_OWNED,
            hints = MYSQL_IMPLICIT_COMMIT_DDL_HINTS,
        )
        rendered += op.id
        if (riskFor(op).destructive) destructive += op.id
        if (op.reversibility == Reversibility.NOT_REVERSIBLE) nonReversible += op.id
        if (riskFor(op).requiresManualConfirmation) manualActions += op.id
    }

    private fun riskFor(op: DiffOperation): OperationRisk =
        if (direction == MysqlRenderDirection.UP) {
            op.risks.up
        } else {
            op.risks.down ?: error(
                "emit() called for op ${op.id} (reversibility=${op.reversibility}) in DOWN direction " +
                    "but risks.down is null; the dispatcher should have skipped or blocked first.",
            )
        }

    fun skip(op: DiffOperation, message: String, code: String = "MYSQL_RENDER_SKIP") {
        skipped += op.id
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = op.id,
        )
    }

    /**
     * E.1 Routine-Migration Slice C.3: annotate an op with an
     * INFO-level diagnostic that does not skip the op or contribute
     * to a blocker. Used to flag stub bewertungen like
     * `DEPENDENCY_GUARD_HEURISTIC` so reports document that the
     * renderer relied on a heuristic, not a topology proof.
     */
    fun info(op: DiffOperation, message: String, code: String) {
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.INFO,
            operationId = op.id,
        )
    }

    fun addBlocker(reason: MigrationBlockedReason, operationIds: Set<String>) {
        blockers += MigrationBlocker(reason = reason, operationIds = operationIds)
    }

    fun indexTouchesGeometry(table: String, index: IndexDefinition): Boolean {
        val schema = if (direction == MysqlRenderDirection.UP) desiredSchema else currentSchema
        val columns = schema?.tables?.get(table)?.columns.orEmpty()
        return index.columnNames.any { name -> columns[name]?.type is NeutralType.Geometry }
    }

    fun toResult(diff: DiffResult): MigrationDdlResult {
        val plannerBlockers = diff.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
        val combinedDiagnostics = plannerBlockers + diagnostics
        // Planner-emitted blockers (CONSTRAINT_NOT_DIFFABLE etc.) always translate to a
        // DIALECT_UNSUPPORTED_OPERATION blocker — even alongside renderer blockers, so
        // a CLI surfaces every reason the plan cannot run.
        val effectiveBlockers = if (plannerBlockers.isNotEmpty()) {
            blockers + MigrationBlocker(
                reason = MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION,
                diagnostics = plannerBlockers,
            )
        } else {
            blockers
        }
        val primary = effectiveBlockers.firstOrNull()?.reason
        val requiresConfirmation = manualActions.isNotEmpty() || destructive.isNotEmpty()
        return MigrationDdlResult(
            statements = statements,
            operationsRendered = rendered,
            operationsSkipped = skipped,
            manualActions = manualActions,
            destructiveOperations = destructive,
            nonReversibleOperations = nonReversible,
            requiresConfirmation = requiresConfirmation,
            blockers = effectiveBlockers,
            primaryBlockedReason = primary,
            diagnostics = combinedDiagnostics,
            spatialProfile = options.spatialProfile.name,
        )
    }

    private companion object {
        private val MYSQL_IMPLICIT_COMMIT_DDL_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.IMPLICIT_COMMIT,
            lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
            implicitCommitPossible = true,
            sideEffectsPossible = true,
            requiresExclusiveAccess = true,
        )
    }
}
