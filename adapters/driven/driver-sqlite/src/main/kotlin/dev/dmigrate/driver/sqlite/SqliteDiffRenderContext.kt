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
        )
        rendered += op.id
        if (riskFor(op).destructive) destructive += op.id
        if (op.reversibility == Reversibility.NOT_REVERSIBLE) nonReversible += op.id
        if (riskFor(op).requiresManualConfirmation) manualActions += op.id
    }

    private fun riskFor(op: DiffOperation): OperationRisk =
        if (direction == SqliteRenderDirection.UP) op.risks.up
        else op.risks.down ?: OperationRisk.SAFE

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
     */
    fun emitRebuildStatement(sqlText: String, opIds: Set<String>) {
        statements += MigrationDdlStatement(
            sql = sqlText,
            operationIds = opIds,
            risk = OperationRisk(destructive = true, dataLossPossible = true, requiresManualConfirmation = true),
            phase = DiffPhase.TABLES,
        )
    }

    /** Mark an op as rendered without emitting a separate statement (rebuild absorbs it). */
    fun markRendered(op: DiffOperation) {
        rendered += op.id
    }

    /** Apply destructive / manualConfirm / nonReversible flags for the rebuild bucket. */
    fun markBucketDestructive(opIds: Set<String>) {
        destructive += opIds
        manualActions += opIds
    }

    fun addDiagnostic(d: DiffDiagnostic) {
        diagnostics += d
    }

    fun toResult(diff: DiffResult): MigrationDdlResult {
        val plannerBlockers = diff.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
        val combinedDiagnostics = plannerBlockers + diagnostics
        val effectiveBlockers = if (plannerBlockers.isNotEmpty() && blockers.isEmpty()) {
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
