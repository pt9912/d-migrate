package dev.dmigrate.streaming

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.format.data.DataChunkWriter

/**
 * Exports a single table by streaming chunks from [DataReader] to
 * [DataChunkWriter]. Handles marker-based resume, progress reporting,
 * and chunk-level callbacks.
 *
 * Extracted from [StreamingExporter] to allow independent testing of
 * per-table export logic without output routing concerns.
 */
internal class TableExporter(private val reader: DataReader) {

    fun export(
        pool: ConnectionPool,
        table: String,
        filter: dev.dmigrate.core.data.DataFilter?,
        config: PipelineConfig,
        writer: DataChunkWriter,
        counting: CountingOutputStream,
        reporter: ProgressReporter,
        ordinal: Int,
        tableCount: Int,
        resumeMarker: ResumeMarker?,
        onChunkProcessed: (TableChunkProgress) -> Unit,
    ): TableExportSummary {
        reporter.report(ProgressEvent.ExportTableStarted(table, ordinal, tableCount))

        val tableStart = System.nanoTime()
        var rows = 0L
        var chunks = 0L
        var error: String? = null
        var beginCalled = false
        val bytesBefore = counting.count
        var markerIdx: Int = -1
        var tieIdxs: IntArray = IntArray(0)

        try {
            val sequence = if (resumeMarker == null) {
                reader.streamTable(pool, table, filter, config.chunkSize)
            } else {
                reader.streamTable(pool, table, filter, config.chunkSize, resumeMarker)
            }
            sequence.use { seq ->
                for (chunk in seq) {
                    if (!beginCalled) {
                        writer.begin(table, chunk.columns)
                        beginCalled = true
                        if (resumeMarker != null) {
                            markerIdx = chunk.columns.indexOfFirst {
                                it.name.equals(resumeMarker.markerColumn, ignoreCase = true)
                            }
                            tieIdxs = IntArray(resumeMarker.tieBreakerColumns.size) { i ->
                                val col = resumeMarker.tieBreakerColumns[i]
                                chunk.columns.indexOfFirst { it.name.equals(col, ignoreCase = true) }
                            }
                        }
                    }
                    writer.write(chunk)
                    rows += chunk.rows.size.toLong()
                    chunks += 1L
                    reporter.report(ProgressEvent.ExportChunkProcessed(
                        table = table, tableOrdinal = ordinal, tableCount = tableCount,
                        chunkIndex = chunks.toInt(), rowsInChunk = chunk.rows.size.toLong(),
                        rowsProcessed = rows, bytesWritten = counting.count - bytesBefore,
                    ))
                    if (resumeMarker != null && chunk.rows.isNotEmpty()) {
                        val lastRow = chunk.rows.last()
                        val markerValue = if (markerIdx >= 0) lastRow[markerIdx] else null
                        val tieValues: List<Any?> = if (tieIdxs.isEmpty()) {
                            emptyList()
                        } else {
                            tieIdxs.map { i -> if (i >= 0) lastRow[i] else null }
                        }
                        runCatching {
                            onChunkProcessed(
                                TableChunkProgress(
                                    table = table,
                                    rowsProcessed = rows,
                                    chunksProcessed = chunks,
                                    position = ResumeMarker.Position(
                                        lastMarkerValue = markerValue,
                                        lastTieBreakerValues = tieValues,
                                    ),
                                )
                            )
                        }
                    }
                }
                if (!beginCalled) {
                    error = "Reader returned no chunks for table '$table' " +
                        "(violates Plan §6.17 — empty tables must still emit one chunk)"
                } else {
                    writer.end()
                }
            }
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
            if (beginCalled) {
                runCatching { writer.end() }
            }
        }

        val durationMs = (System.nanoTime() - tableStart) / 1_000_000
        val status = if (error == null) TableProgressStatus.COMPLETED else TableProgressStatus.FAILED
        reporter.report(ProgressEvent.ExportTableFinished(
            table = table, tableOrdinal = ordinal, tableCount = tableCount,
            rowsProcessed = rows, chunksProcessed = chunks.toInt(),
            bytesWritten = counting.count - bytesBefore, durationMs = durationMs,
            status = status,
        ))

        return TableExportSummary(
            table = table,
            rows = rows,
            chunks = chunks,
            bytes = counting.count - bytesBefore,
            durationMs = durationMs,
            error = error,
        )
    }
}
