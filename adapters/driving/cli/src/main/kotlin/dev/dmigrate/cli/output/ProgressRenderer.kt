package dev.dmigrate.cli.output

import dev.dmigrate.streaming.ProgressEvent
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.TableProgressStatus
import java.util.Locale

class ProgressRenderer(
    private val stderr: (String) -> Unit = { System.err.println(it) },
) : ProgressReporter {

    override fun report(event: ProgressEvent) {
        stderr(render(event))
    }

    internal fun render(event: ProgressEvent): String = when (event) {
        is ProgressEvent.RunStarted ->
            "${opLabel(event.operation.name)} ${event.totalTables} table(s)"

        is ProgressEvent.ExportTableStarted ->
            "Exporting table '${event.table}' (${event.tableOrdinal}/${event.tableCount})"

        is ProgressEvent.ExportChunkProcessed ->
            "Exporting table '${event.table}' | chunk ${event.chunkIndex} | ${formatNumber(event.rowsInChunk)} rows | ${formatMb(event.bytesWritten)}"

        is ProgressEvent.ExportTableFinished ->
            "${statusPrefix(event.status)} table '${event.table}' | ${formatNumber(event.rowsProcessed)} rows | ${event.chunksProcessed} chunks | ${formatMb(event.bytesWritten)}"

        is ProgressEvent.ImportTableStarted ->
            "Importing table '${event.table}' (${event.tableOrdinal}/${event.tableCount})"

        is ProgressEvent.ImportChunkProcessed -> {
            val parts = mutableListOf<String>()
            parts += "${formatNumber(event.rowsInChunk)} rows processed"
            if (event.rowsInserted > 0) parts += "${formatNumber(event.rowsInserted)} inserted"
            if (event.rowsUpdated > 0) parts += "${formatNumber(event.rowsUpdated)} updated"
            if (event.rowsSkipped > 0) parts += "${formatNumber(event.rowsSkipped)} skipped"
            if (event.rowsFailed > 0) parts += "${formatNumber(event.rowsFailed)} failed"
            "Importing table '${event.table}' | chunk ${event.chunkIndex} | ${parts.joinToString(", ")}"
        }

        is ProgressEvent.ImportTableFinished -> {
            val parts = mutableListOf<String>()
            parts += "${formatNumber(event.rowsInserted)} inserted"
            if (event.rowsUpdated > 0) parts += "${formatNumber(event.rowsUpdated)} updated"
            if (event.rowsSkipped > 0) parts += "${formatNumber(event.rowsSkipped)} skipped"
            if (event.rowsFailed > 0) parts += "${formatNumber(event.rowsFailed)} failed"
            val prefix = if (event.status == TableProgressStatus.COMPLETED) "Imported" else "FAILED"
            "$prefix table '${event.table}' | ${parts.joinToString(", ")}"
        }
    }

    private fun opLabel(op: String): String = when (op) {
        "EXPORT" -> "Exporting"
        "IMPORT" -> "Importing"
        else -> op
    }

    private fun statusPrefix(status: TableProgressStatus): String = when (status) {
        TableProgressStatus.COMPLETED -> "Exported"
        TableProgressStatus.FAILED -> "FAILED"
    }

    companion object {
        internal fun formatNumber(n: Long): String =
            String.format(Locale.US, "%,d", n)

        internal fun formatMb(bytes: Long): String =
            String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1024 * 1024))
    }
}
