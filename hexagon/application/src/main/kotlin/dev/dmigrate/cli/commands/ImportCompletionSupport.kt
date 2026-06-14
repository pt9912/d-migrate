package dev.dmigrate.cli.commands

import dev.dmigrate.streaming.ImportResult
import dev.dmigrate.streaming.checkpoint.CheckpointStore
import dev.dmigrate.streaming.checkpoint.CheckpointStoreException
import java.util.Locale

internal sealed interface ImportCompletionAssessment {
    data object Success : ImportCompletionAssessment
    data class Exit(val code: Int, val message: String) : ImportCompletionAssessment
}

internal object ImportCompletionSupport {

    /**
     * @param isParquetBundle S9a-0.d (AP8 §7.3 / AP12 §9): bei einem
     *   Parquet-Bundle-Lauf bekommt ein Per-Tabelle-Fehler den stabilen
     *   Code `BUNDLE_TABLE_IMPORT_FAILED` (Exit 5 bleibt unveraendert).
     *   Andere Quellen (JSON/YAML/CSV/Single-File) behalten die generische
     *   Meldung. Der Code deckt den bestehenden, erreichbaren Fehlerpfad
     *   ab (Tabelle scheitert in Reader/Writer/Commit/Finish) — er ist
     *   keine defensive Platzhalter-Diagnose.
     */
    fun assessCompletion(result: ImportResult, isParquetBundle: Boolean = false): ImportCompletionAssessment {
        val failedTable = result.tables.firstOrNull { it.error != null }
        if (failedTable != null) {
            val message = if (isParquetBundle) {
                "Error: BUNDLE_TABLE_IMPORT_FAILED: table='${failedTable.table}' " +
                    "cause='${failedTable.error}'"
            } else {
                "Error: Failed to import table '${failedTable.table}': ${failedTable.error}"
            }
            return ImportCompletionAssessment.Exit(code = 5, message = message)
        }

        val failedFinish = result.tables.firstOrNull { it.failedFinish != null }
        if (failedFinish != null) {
            val cause = failedFinish.failedFinish!!.causeMessage
            val message = if (isParquetBundle) {
                "Error: BUNDLE_TABLE_IMPORT_FAILED: table='${failedFinish.table}' cause='$cause' " +
                    "(post-import finalization; data was committed - manual fix may be needed)"
            } else {
                "Error: Post-import finalization failed for table '${failedFinish.table}': " +
                    "$cause. Data was committed - manual post-import fix may be needed."
            }
            return ImportCompletionAssessment.Exit(code = 5, message = message)
        }

        return ImportCompletionAssessment.Success
    }

    fun finalizeAndReport(
        request: DataImportRequest,
        result: ImportResult,
        store: CheckpointStore?,
        operationId: String,
        stderr: (String) -> Unit,
        isParquetBundle: Boolean = false,
    ): Int {
        when (val assessment = assessCompletion(result, isParquetBundle)) {
            is ImportCompletionAssessment.Exit -> {
                stderr(assessment.message)
                return assessment.code
            }
            ImportCompletionAssessment.Success -> Unit
        }

        if (store != null) {
            try {
                store.complete(operationId)
            } catch (e: CheckpointStoreException) {
                stderr("Warning: Failed to remove completed checkpoint: ${e.message}")
            }
        }

        val suppressProgress = request.quiet || request.noProgress
        if (!suppressProgress) {
            stderr(formatProgressSummary(result))
            result.operationId?.let { stderr("Run operation id: $it") }
        }

        return 0
    }

    fun formatProgressSummary(result: ImportResult): String {
        val tableCount = result.tables.size
        val totalInserted = result.totalRowsInserted
        val totalUpdated = result.totalRowsUpdated
        val totalFailed = result.totalRowsFailed
        val seqCount = result.tables.flatMap { it.sequenceAdjustments }.size
        val durationSec = result.durationMs / 1000.0

        val parts = mutableListOf<String>()
        parts += "$totalInserted inserted"
        if (totalUpdated > 0) parts += "$totalUpdated updated"
        if (totalFailed > 0) parts += "$totalFailed failed"
        val rowsSummary = parts.joinToString(", ")

        val seqInfo = if (seqCount > 0) "; reseeded $seqCount sequence(s)" else ""
        return "Imported $tableCount table(s) ($rowsSummary) in ${"%.1f".format(Locale.US, durationSec)} s$seqInfo"
    }
}
