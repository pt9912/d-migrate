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
        val rowsFailed: Long,
        val durationMs: Long,
        val status: TableProgressStatus,
    ) : ProgressEvent
}
