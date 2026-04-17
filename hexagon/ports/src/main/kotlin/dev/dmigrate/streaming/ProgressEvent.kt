package dev.dmigrate.streaming

enum class ProgressOperation { EXPORT, IMPORT }

enum class TableProgressStatus { COMPLETED, FAILED }

fun interface ProgressReporter {
    fun report(event: ProgressEvent)
}

object NoOpProgressReporter : ProgressReporter {
    override fun report(event: ProgressEvent) = Unit
}

sealed interface ProgressEvent {

    data class RunStarted(
        val operation: ProgressOperation,
        val totalTables: Int,
        /**
         * 0.9.0 Phase B (`docs/ImpPlan-0.9.0-B.md` §4.5): stabile
         * `operationId` fuer den gesamten Lauf. Wird in
         * [ExportResult.operationId]/[ImportResult.operationId]
         * gespiegelt und darf in den stderr-nahen Progress-/Summary-
         * Pfaden nicht verloren gehen. Phase B-konforme Erzeuger
         * setzen den Wert; Legacy-Callsites, die noch keine
         * operationId haben, duerfen temporaer `null` uebergeben.
         */
        val operationId: String? = null,
        /**
         * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §3.1 / §5.3):
         * `true`, wenn der Lauf eine Wiederaufnahme aus einem vorhandenen
         * Checkpoint-Manifest ist. Der `ProgressRenderer` zeigt in diesem
         * Fall ein „Resuming run …"-Label; bei `false` ein
         * „Starting run …"-Label.
         */
        val resuming: Boolean = false,
    ) : ProgressEvent

    // ── Export ─────────────────────────────────────────────

    data class ExportTableStarted(
        val table: String,
        val tableOrdinal: Int,
        val tableCount: Int,
    ) : ProgressEvent

    data class ExportChunkProcessed(
        val table: String,
        val tableOrdinal: Int,
        val tableCount: Int,
        val chunkIndex: Int,
        val rowsInChunk: Long,
        val rowsProcessed: Long,
        val bytesWritten: Long,
    ) : ProgressEvent

    data class ExportTableFinished(
        val table: String,
        val tableOrdinal: Int,
        val tableCount: Int,
        val rowsProcessed: Long,
        val chunksProcessed: Int,
        val bytesWritten: Long,
        val durationMs: Long,
        val status: TableProgressStatus,
    ) : ProgressEvent

    // ── Import ─────────────────────────────────────────────

    data class ImportTableStarted(
        val table: String,
        val tableOrdinal: Int,
        val tableCount: Int,
    ) : ProgressEvent

    data class ImportChunkProcessed(
        val table: String,
        val tableOrdinal: Int,
        val tableCount: Int,
        val chunkIndex: Int,
        val rowsInChunk: Long,
        val rowsProcessed: Long,
        val rowsInserted: Long,
        val rowsUpdated: Long,
        val rowsSkipped: Long,
        val rowsUnknown: Long,
        val rowsFailed: Long,
    ) : ProgressEvent

    data class ImportTableFinished(
        val table: String,
        val tableOrdinal: Int,
        val tableCount: Int,
        val rowsInserted: Long,
        val rowsUpdated: Long,
        val rowsSkipped: Long,
        val rowsUnknown: Long,
        val rowsFailed: Long,
        val durationMs: Long,
        val status: TableProgressStatus,
    ) : ProgressEvent
}
