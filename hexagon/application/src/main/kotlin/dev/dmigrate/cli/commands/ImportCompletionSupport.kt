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

    fun assessCompletion(result: ImportResult): ImportCompletionAssessment {
        val failedTable = result.tables.firstOrNull { it.error != null }
        if (failedTable != null) {
            return ImportCompletionAssessment.Exit(
                code = 5,
                message = "Error: Failed to import table '${failedTable.table}': ${failedTable.error}",
            )
        }

        val failedFinish = result.tables.firstOrNull { it.failedFinish != null }
        if (failedFinish != null) {
            return ImportCompletionAssessment.Exit(
                code = 5,
                message = "Error: Post-import finalization failed for table '${failedFinish.table}': " +
                    "${failedFinish.failedFinish!!.causeMessage}. " +
                    "Data was committed - manual post-import fix may be needed.",
            )
        }

        return ImportCompletionAssessment.Success
    }

    fun finalizeAndReport(
        request: DataImportRequest,
        result: ImportResult,
        store: CheckpointStore?,
        operationId: String,
        stderr: (String) -> Unit,
    ): Int {
        when (val assessment = assessCompletion(result)) {
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
