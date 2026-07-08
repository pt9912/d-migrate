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
 * See `docs/planning/done-archive/diffresult-migration-plan.md §4.6`.
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
 * - [hasGap]: the operation renders as two or more statements that
 *   leave a short window in which the schema object is missing or
 *   inconsistent — e.g. `ReplaceTrigger` via Drop+Create on a dialect
 *   without native `CREATE OR REPLACE TRIGGER`. The strict execution
 *   mode treats this as `MANUAL_ACTION_REQUIRED`; the default mode
 *   surfaces it as a `W_*_GAP` warning. Renderers must not set this
 *   flag for atomic operations that complete in a single statement.
 *
 * [notes] holds zero or more diagnostic messages the generator wants
 * to surface to operator-facing reports.
 */
data class OperationRisk(
    val destructive: Boolean = false,
    val dataLossPossible: Boolean = false,
    val requiresTableRewrite: Boolean = false,
    val requiresManualConfirmation: Boolean = false,
    val hasGap: Boolean = false,
    val dataTransformation: DataTransformationContract = DataTransformationContract.NONE,
    val notes: List<DiffDiagnostic> = emptyList(),
) {
    companion object {
        /** Convenience: a fully safe operation with no flags raised. */
        val SAFE: OperationRisk = OperationRisk()
    }
}

/**
 * F.1 contract for data-changing logic beyond schema DDL.
 *
 * The default is deliberately [DataTransformationMode.NONE]. A future
 * automatic backfill or data rewrite must carry an explicit, versioned model
 * per direction; otherwise callers must keep the operation manual.
 */
data class DataTransformationContract(
    val mode: DataTransformationMode,
    val modelVersion: String? = null,
    val modelId: String? = null,
    val description: String? = null,
) {
    init {
        if (mode == DataTransformationMode.AUTOMATIC) {
            require(!modelVersion.isNullOrBlank()) {
                "Automatic data transformations require a modelVersion"
            }
            require(!modelId.isNullOrBlank()) {
                "Automatic data transformations require a modelId"
            }
        }
        if (mode == DataTransformationMode.NONE) {
            require(modelVersion == null && modelId == null && description == null) {
                "NONE data transformations cannot carry transformation metadata"
            }
        }
    }

    companion object {
        val NONE: DataTransformationContract = DataTransformationContract(DataTransformationMode.NONE)

        fun manualRequired(description: String): DataTransformationContract =
            DataTransformationContract(
                mode = DataTransformationMode.MANUAL_REQUIRED,
                description = description,
            )

        fun automatic(
            modelVersion: String,
            modelId: String,
            description: String? = null,
        ): DataTransformationContract =
            DataTransformationContract(
                mode = DataTransformationMode.AUTOMATIC,
                modelVersion = modelVersion,
                modelId = modelId,
                description = description,
            )
    }
}

enum class DataTransformationMode {
    NONE,
    MANUAL_REQUIRED,
    AUTOMATIC,
}
