package dev.dmigrate.cli.output

import dev.dmigrate.streaming.ProgressEvent
import dev.dmigrate.streaming.ProgressReporter
import dev.dmigrate.streaming.TableProgressStatus
import java.util.Locale

class ProgressRenderer(
    private val messages: MessageResolver = MessageResolver(Locale.ENGLISH),
    private val stderr: (String) -> Unit = { System.err.println(it) },
) : ProgressReporter {

    override fun report(event: ProgressEvent) {
        stderr(render(event))
    }

    internal fun render(event: ProgressEvent): String = when (event) {
        is ProgressEvent.RunStarted -> {
            val baseKey = if (event.operation.name == "EXPORT")
                "cli.progress.run_started_export" else "cli.progress.run_started_import"
            val base = messages.text(baseKey, event.totalTables)
            // 0.9.0 Phase C.1 (docs/ImpPlan-0.9.0-C1.md §5.3): bei
            // vorhandener operationId zeigt der Renderer sichtbar,
            // ob der Lauf ein neuer oder eine Wiederaufnahme ist.
            // Bestehende Snapshot-Tests (ohne operationId) bleiben
            // unveraendert — nur wenn die Runner-Seite die ID liefert,
            // wird das Phase-C.1-Label sichtbar.
            if (event.operationId != null) {
                val prefix = if (event.resuming) "Resuming run" else "Starting run"
                "$prefix ${event.operationId}: $base"
            } else {
                base
            }
        }

        is ProgressEvent.ExportTableStarted ->
            messages.text("cli.progress.export_table_started",
                event.table, event.tableOrdinal, event.tableCount)

        is ProgressEvent.ExportChunkProcessed ->
            messages.text("cli.progress.export_chunk",
                event.table, event.chunkIndex, formatNumber(event.rowsProcessed), formatMb(event.bytesWritten))

        is ProgressEvent.ExportTableFinished -> {
            val key = if (event.status == TableProgressStatus.COMPLETED)
                "cli.progress.export_table_completed" else "cli.progress.export_table_failed"
            messages.text(key, event.table, formatNumber(event.rowsProcessed),
                event.chunksProcessed, formatMb(event.bytesWritten))
        }

        is ProgressEvent.ImportTableStarted ->
            messages.text("cli.progress.import_table_started",
                event.table, event.tableOrdinal, event.tableCount)

        is ProgressEvent.ImportChunkProcessed -> {
            val parts = mutableListOf<String>()
            parts += messages.text("cli.progress.rows_processed", formatNumber(event.rowsInChunk))
            if (event.rowsInserted > 0) parts += messages.text("cli.progress.rows_inserted", formatNumber(event.rowsInserted))
            if (event.rowsUpdated > 0) parts += messages.text("cli.progress.rows_updated", formatNumber(event.rowsUpdated))
            if (event.rowsSkipped > 0) parts += messages.text("cli.progress.rows_skipped", formatNumber(event.rowsSkipped))
            if (event.rowsFailed > 0) parts += messages.text("cli.progress.rows_failed", formatNumber(event.rowsFailed))
            messages.text("cli.progress.import_chunk", event.table, event.chunkIndex, parts.joinToString(", "))
        }

        is ProgressEvent.ImportTableFinished -> {
            val parts = mutableListOf<String>()
            parts += messages.text("cli.progress.rows_inserted", formatNumber(event.rowsInserted))
            if (event.rowsUpdated > 0) parts += messages.text("cli.progress.rows_updated", formatNumber(event.rowsUpdated))
            if (event.rowsSkipped > 0) parts += messages.text("cli.progress.rows_skipped", formatNumber(event.rowsSkipped))
            if (event.rowsUnknown > 0) parts += messages.text("cli.progress.rows_unknown", formatNumber(event.rowsUnknown))
            if (event.rowsFailed > 0) parts += messages.text("cli.progress.rows_failed", formatNumber(event.rowsFailed))
            val key = if (event.status == TableProgressStatus.COMPLETED)
                "cli.progress.import_table_completed" else "cli.progress.import_table_failed"
            messages.text(key, event.table, parts.joinToString(", "))
        }
    }

    companion object {
        // Technical number formatting stays Locale.US for stability
        internal fun formatNumber(n: Long): String =
            String.format(Locale.US, "%,d", n)

        internal fun formatMb(bytes: Long): String =
            String.format(Locale.US, "%.2f MB", bytes.toDouble() / (1024 * 1024))
    }
}
