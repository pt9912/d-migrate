package dev.dmigrate.core.diff.migration

/**
 * Direction-specific risk profile for a [DiffOperation].
 *
 * The Up-generator reads `risks.up`; the Down-generator builds inverse
 * operations and assigns their own [OperationRisk] (NOT a copy of the
 * Up-risk — an `AddColumn` Up is non-destructive but its Down `DropColumn`
 * usually is). When [Reversibility] is `NOT_REVERSIBLE` or
 * `MANUAL_REQUIRED`, [down] may be `null` and the blocker comes from the
 * reversibility classification + generator diagnostics.
 *
 * See `docs/planning/done/diffresult-migration-plan.md §4.6`.
 */
data class OperationRisks(
    val up: OperationRisk,
    val down: OperationRisk? = null,
)

/**
 * Risk profile for a single execution direction (Up or Down).
 *
 * Flag conventions:
 *
 * - [destructive]: removes schema state that cannot be recovered from
 *   schema metadata alone (e.g. `DropTable`, `DropColumn`).
 * - [dataLossPossible]: may discard rows or column values
 *   (`DropColumn`, type-narrowing `AlterColumnType`).
 * - [requiresTableRewrite]: the dialect needs a full table-rebuild to
 *   apply (SQLite `RebuildTable`, MySQL `ALTER TABLE` for non-online
 *   ops, etc.). Used by the runner to advise downtime/retry windows.
 * - [requiresManualConfirmation]: requires `--allow-destructive` on
 *   the CLI side. Distinct from [destructive] because some
 *   [destructive] ops only carry the data-loss flag for the Down
 *   direction; only when the runner is asked to actually execute the
 *   destructive direction does it need confirmation.
 *
 * [notes] holds zero or more diagnostic messages the generator wants
 * to surface to operator-facing reports.
 */
data class OperationRisk(
    val destructive: Boolean = false,
    val dataLossPossible: Boolean = false,
    val requiresTableRewrite: Boolean = false,
    val requiresManualConfirmation: Boolean = false,
    val notes: List<DiffDiagnostic> = emptyList(),
) {
    companion object {
        /** Convenience: a fully safe operation with no flags raised. */
        val SAFE: OperationRisk = OperationRisk()
    }
}
