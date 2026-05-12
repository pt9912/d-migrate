package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionScope

/** Rendering direction. */
internal enum class SqliteRenderDirection { UP, DOWN }

/** Mutable accumulator for one SQLite renderer invocation. Mirrors the PG / MySQL contexts. */
internal class SqliteDiffRenderContext(
    val direction: SqliteRenderDirection,
    val sql: SqliteDiffSqlBuilders,
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

    fun emit(op: DiffOperation, sqlText: String) {
        statements += MigrationDdlStatement(
            sql = sqlText,
            operationIds = setOf(op.id),
            risk = riskFor(op),
            phase = op.phase,
            transactionScope = TransactionScope.RUNNER_OWNED,
        )
        rendered += op.id
        if (riskFor(op).destructive) destructive += op.id
        if (op.reversibility == Reversibility.NOT_REVERSIBLE) nonReversible += op.id
        if (riskFor(op).requiresManualConfirmation) manualActions += op.id
    }

    private fun riskFor(op: DiffOperation): OperationRisk =
        if (direction == SqliteRenderDirection.UP) {
            op.risks.up
        } else {
            op.risks.down ?: error(
                "emit() called for op ${op.id} (reversibility=${op.reversibility}) in DOWN direction " +
                    "but risks.down is null; the dispatcher should have skipped or blocked first.",
            )
        }

    fun skip(op: DiffOperation, message: String, code: String = "SQLITE_RENDER_SKIP") {
        skipped += op.id
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.BLOCKER,
            operationId = op.id,
        )
    }

    /**
     * Mark an operation as deferred to the future RebuildTable
     * pipeline (D.4.b). Does not emit DDL; surfaces a
     * `MANUAL_ACTION_REQUIRED` blocker referring to Plan §6.4.
     */
    fun deferToRebuild(op: DiffOperation) {
        skip(
            op,
            "SQLite cannot ALTER this aspect of column/constraint without a full table rebuild. " +
                "The D.4.a renderer surfaces this as MANUAL_ACTION_REQUIRED; the rebuild " +
                "pipeline lands in D.4.b (Plan §6.4).",
            code = "SQLITE_REBUILD_REQUIRED",
        )
        addBlocker(MigrationBlockedReason.MANUAL_ACTION_REQUIRED, operationIds = setOf(op.id))
    }

    fun addBlocker(reason: MigrationBlockedReason, operationIds: Set<String>) {
        blockers += MigrationBlocker(reason = reason, operationIds = operationIds)
    }

    /**
     * Emits a single statement attached to a *set* of operation IDs.
     * Used by the RebuildTable pipeline where one rebuild covers
     * multiple business operations on the same table.
     *
     * The [risk] is supplied per statement so that bookkeeping
     * statements (PRAGMAs, BEGIN/COMMIT) can be tagged SAFE while
     * destructive steps (DROP TABLE, INSERT-SELECT) carry the
     * bucket-derived risk projection.
     *
     * The [phase] follows the §4.4 phase ordering: PRAGMA wrapping
     * and BEGIN sit in PREPARE; CREATE/INSERT/DROP/RENAME in TABLES;
     * recreated indices in INDEXES; the closing PRAGMA/COMMIT in
     * CLEANUP. This lets dry-run / staged-execute filters address
     * sub-ranges of the rebuild without string-matching SQL.
     */
    fun emitRebuildStatement(
        sqlText: String,
        opIds: Set<String>,
        risk: OperationRisk = OperationRisk.SAFE,
        phase: DiffPhase = DiffPhase.TABLES,
    ) {
        // Plan-2 §G.1: the SQLite rebuild pipeline emits its own
        // BEGIN IMMEDIATE / COMMIT bracket plus pre-/post-PRAGMAs.
        // The whole rebuild group is dispatched as STREAM_OWNED so
        // the executor leaves autoCommit untouched. Per-statement
        // boundary refinement (PRAGMA vs. inside-tx vs. COMMIT) is
        // tracked as Plan-2 §G.3 (`transactionBoundary`).
        statements += MigrationDdlStatement(
            sql = sqlText,
            operationIds = opIds,
            risk = risk,
            phase = phase,
            transactionScope = TransactionScope.STREAM_OWNED,
        )
    }

    /** Mark an op as rendered without emitting a separate statement (rebuild absorbs it). */
    fun markRendered(op: DiffOperation) {
        rendered += op.id
    }

    /**
     * Project an `OperationRisk` summary across a rebuild bucket: any
     * op that is destructive / lossy / requires confirmation in the
     * current direction propagates that flag to the bucket-level risk.
     * `requiresTableRewrite` is always set for a rebuild — that's the
     * defining characteristic of the bucket.
     */
    fun bucketRisk(bucket: List<DiffOperation>): OperationRisk {
        var destructive = false
        var dataLossPossible = false
        var requiresManualConfirmation = false
        for (op in bucket) {
            val r = riskFor(op)
            if (r.destructive) destructive = true
            if (r.dataLossPossible) dataLossPossible = true
            if (r.requiresManualConfirmation) requiresManualConfirmation = true
        }
        return OperationRisk(
            destructive = destructive,
            dataLossPossible = dataLossPossible,
            requiresTableRewrite = true,
            requiresManualConfirmation = requiresManualConfirmation,
        )
    }

    /**
     * Reflect the bucket's projected risk into the context-level
     * tracking sets. Called after [bucketRisk] has been computed and
     * the rebuild statements emitted.
     */
    fun applyBucketRisk(opIds: Set<String>, risk: OperationRisk) {
        if (risk.destructive) destructive += opIds
        if (risk.requiresManualConfirmation) manualActions += opIds
    }

    fun addDiagnostic(d: DiffDiagnostic) {
        diagnostics += d
    }

    fun toResult(diff: DiffResult): MigrationDdlResult {
        val plannerBlockers = diff.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
        val combinedDiagnostics = plannerBlockers + diagnostics
        // Planner-emitted blockers (CONSTRAINT_NOT_DIFFABLE etc.) always translate to a
        // DIALECT_UNSUPPORTED_OPERATION blocker — even alongside renderer blockers.
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
}
