package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.DialectExecutionHints
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionBehavior
import dev.dmigrate.driver.migration.TransactionScope

/**
 * Rendering direction. Up emits the migration as planned; Down
 * walks the topo-sort in reverse and applies inverse semantics.
 */
internal enum class PostgresRenderDirection { UP, DOWN }

/**
 * Mutable accumulator for one renderer invocation. Owns the
 * statement list and the four bookkeeping sets (rendered, skipped,
 * destructive, non-reversible) plus blocker / diagnostic ledgers,
 * and projects the per-op risk according to the rendering direction.
 */
internal class PostgresDiffRenderContext(
    val direction: PostgresRenderDirection,
    val sql: PostgresDiffSqlBuilders,
    @Suppress("unused") val options: DdlGenerationOptions,
) {
    private val statements = mutableListOf<MigrationDdlStatement>()
    private val rendered = mutableSetOf<String>()
    private val skipped = mutableSetOf<String>()
    private val manualActions = mutableSetOf<String>()
    private val destructive = mutableSetOf<String>()
    private val nonReversible = mutableSetOf<String>()
    private val blockers = mutableListOf<MigrationBlocker>()
    private val diagnostics = mutableListOf<DiffDiagnostic>()

    fun emit(
        op: DiffOperation,
        sqlText: String,
        hints: DialectExecutionHints = POSTGRES_TRANSACTIONAL_DDL_HINTS,
    ) {
        // Plan-2 §A.1: PostgreSQL DDL is fully transactional. The
        // default `hints` cover the lock-heavy ALTER TABLE / DROP
        // TABLE / CREATE TABLE / DROP INDEX / constraint paths
        // (TABLE_EXCLUSIVE + requiresExclusiveAccess). Callers
        // emitting lighter-lock DDL override:
        //   - CREATE INDEX (non-CONCURRENTLY) → SHARE lock →
        //     [POSTGRES_CREATE_INDEX_HINTS]
        //   - CREATE/DROP TYPE, CREATE/DROP/REPLACE VIEW →
        //     pg_type / view catalog only →
        //     [POSTGRES_METADATA_HINTS]
        // CREATE INDEX CONCURRENTLY (TransactionScope.NO_TRANSACTION +
        // NOT_TRANSACTIONAL) is not yet rendered by this adapter.
        statements += MigrationDdlStatement(
            sql = sqlText,
            operationIds = setOf(op.id),
            risk = riskFor(op),
            phase = op.phase,
            transactionScope = TransactionScope.RUNNER_OWNED,
            hints = hints,
        )
        rendered += op.id
        if (riskFor(op).destructive) destructive += op.id
        if (op.reversibility == Reversibility.NOT_REVERSIBLE) nonReversible += op.id
        if (riskFor(op).requiresManualConfirmation) manualActions += op.id
    }

    private fun riskFor(op: DiffOperation): OperationRisk =
        if (direction == PostgresRenderDirection.UP) {
            op.risks.up
        } else {
            // NOT_REVERSIBLE / MANUAL_REQUIRED ops should be short-circuited by the
            // dispatcher before reaching emit(), so a null down-risk here is a contract
            // violation by the caller. Loud failure beats silent SAFE.
            op.risks.down ?: error(
                "emit() called for op ${op.id} (reversibility=${op.reversibility}) in DOWN direction " +
                    "but risks.down is null; the dispatcher should have skipped or blocked first.",
            )
        }

    fun skip(op: DiffOperation, message: String, code: String = "POSTGRES_RENDER_SKIP") {
        skipped += op.id
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = op.id,
        )
    }

    fun addBlocker(reason: MigrationBlockedReason, operationIds: Set<String>) {
        blockers += MigrationBlocker(reason = reason, operationIds = operationIds)
    }

    fun addInfoDiagnostic(code: String, operationId: String, message: String) {
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.INFO,
            operationId = operationId,
        )
    }

    fun toResult(diff: DiffResult): MigrationDdlResult {
        val plannerBlockers = diff.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
        val combinedDiagnostics = plannerBlockers + diagnostics
        // Planner-emitted blockers (CONSTRAINT_NOT_DIFFABLE etc.) always translate to a
        // DIALECT_UNSUPPORTED_OPERATION blocker on the renderer side — even when the
        // renderer also has its own blockers. A CLI consumer that reads only the
        // `blockers` list must see *every* reason the plan can't run.
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
        )
    }

    companion object {
        /**
         * Default PostgreSQL DDL hint: fully transactional under the
         * runner-owned tx, takes an exclusive table lock for the
         * statement's duration. Covers ALTER TABLE (incl. ADD/DROP
         * COLUMN, ALTER COLUMN, ADD/DROP CONSTRAINT, ADD/DROP PK),
         * CREATE TABLE, DROP TABLE, DROP INDEX.
         */
        internal val POSTGRES_TRANSACTIONAL_DDL_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
            implicitCommitPossible = false,
            sideEffectsPossible = false,
            requiresExclusiveAccess = true,
        )

        /**
         * Hint for `CREATE INDEX` (non-CONCURRENTLY) on a regular
         * table: PostgreSQL takes a SHARE lock — writes block, reads
         * proceed. Honest LockBehavior is TABLE_SHARED;
         * `requiresExclusiveAccess` is false because concurrent
         * readers are fine.
         */
        internal val POSTGRES_CREATE_INDEX_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.TABLE_SHARED,
            implicitCommitPossible = false,
            sideEffectsPossible = false,
            requiresExclusiveAccess = false,
        )

        /**
         * Hint for catalog-only operations that don't touch user
         * tables: CREATE/DROP TYPE (writes `pg_type`), CREATE/DROP/
         * CREATE OR REPLACE VIEW (writes the view's `pg_class` row;
         * takes AccessShareLock on referenced relations during
         * planning, not exclusive). LockBehavior is METADATA;
         * `requiresExclusiveAccess` is false because no user-data
         * table is locked.
         */
        internal val POSTGRES_METADATA_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
            lockBehavior = LockBehavior.METADATA,
            implicitCommitPossible = false,
            sideEffectsPossible = false,
            requiresExclusiveAccess = false,
        )
    }
}
