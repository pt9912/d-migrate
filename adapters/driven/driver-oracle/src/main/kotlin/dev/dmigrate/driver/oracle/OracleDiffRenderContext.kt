package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffDiagnostic
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.Reversibility
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.NoteType
import dev.dmigrate.driver.TransformationNote
import dev.dmigrate.driver.migration.DialectExecutionHints
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.MigrationBlocker
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import dev.dmigrate.driver.migration.TransactionBehavior
import dev.dmigrate.driver.migration.TransactionScope

/**
 * Rendering direction. Up emits the migration as planned; Down walks the
 * topo-sort in reverse and applies inverse semantics.
 */
internal enum class OracleRenderDirection { UP, DOWN }

/**
 * Mutable accumulator for one renderer invocation. Owns the statement list
 * and bookkeeping sets, projects per-op risk according to direction.
 */
internal class OracleDiffRenderContext(
    val direction: OracleRenderDirection,
    val sql: OracleDiffSqlBuilders,
    val options: DdlGenerationOptions,
    private val currentSchema: SchemaDefinition? = null,
    private val desiredSchema: SchemaDefinition? = null,
) {
    private val statements = mutableListOf<MigrationDdlStatement>()
    private val rendered = mutableSetOf<String>()
    private val skipped = mutableSetOf<String>()
    private val manualActions = mutableSetOf<String>()
    private val destructive = mutableSetOf<String>()
    private val nonReversible = mutableSetOf<String>()
    private val blockers = mutableListOf<MigrationBlocker>()
    private val diagnostics = mutableListOf<DiffDiagnostic>()

    fun emit(op: DiffOperation, sqlText: String, hints: DialectExecutionHints = ORACLE_IMPLICIT_COMMIT_DDL_HINTS) {
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
        if (direction == OracleRenderDirection.UP) {
            op.risks.up
        } else {
            op.risks.down ?: error(
                "emit() called for op ${op.id} (reversibility=${op.reversibility}) in DOWN direction " +
                    "but risks.down is null; the dispatcher should have skipped or blocked first.",
            )
        }

    fun skip(op: DiffOperation, message: String, code: String = "ORACLE_RENDER_SKIP") {
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

    fun warning(op: DiffOperation, message: String, code: String) {
        diagnostics += DiffDiagnostic(
            code = code,
            message = message,
            severity = DiffDiagnostic.Severity.WARNING,
            operationId = op.id,
        )
    }

    /** Traegt die Notizen eines wiederverwendeten Generate-Helfers (z. B. [OracleColumnConstraintHelper]) als Diagnosen nach. */
    fun carryOverNotes(op: DiffOperation, notes: List<TransformationNote>) {
        for (note in notes) {
            diagnostics += DiffDiagnostic(
                code = note.code,
                message = note.message,
                severity = when (note.type) {
                    NoteType.ACTION_REQUIRED, NoteType.WARNING -> DiffDiagnostic.Severity.WARNING
                    NoteType.INFO -> DiffDiagnostic.Severity.INFO
                },
                operationId = op.id,
            )
        }
    }

    /** Das Schema auf der Seite, die diese Richtung liest (UP=desired, DOWN=current). */
    fun schemaForDirection(): SchemaDefinition? =
        if (direction == OracleRenderDirection.UP) desiredSchema else currentSchema

    /** Spaltendefinition von `table.column` auf der Seite, die diese Richtung liest. */
    fun columnFor(table: String, column: String): ColumnDefinition? =
        schemaForDirection()?.tables?.get(table)?.columns?.get(column)

    fun toResult(diff: DiffResult): MigrationDdlResult {
        val plannerBlockers = diff.diagnostics.filter { it.severity == DiffDiagnostic.Severity.BLOCKER }
        val combinedDiagnostics = plannerBlockers + diagnostics
        val effectiveBlockers = if (plannerBlockers.isEmpty()) {
            blockers
        } else {
            blockers + plannerBlockers
                .groupBy { PlannerBlockerClassifier.classify(it.code) }
                .map { (reason, diags) -> MigrationBlocker(reason = reason, diagnostics = diags) }
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

    companion object {
        /**
         * Oracle-DDL committet implizit vor UND nach jeder Anweisung (wie MySQL,
         * anders als PostgreSQL/SQL Server) -- kein Rollback ueber mehrere
         * Statements hinweg moeglich.
         */
        internal val ORACLE_IMPLICIT_COMMIT_DDL_HINTS = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.IMPLICIT_COMMIT,
            lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
            implicitCommitPossible = true,
            sideEffectsPossible = true,
            requiresExclusiveAccess = true,
        )
    }
}
