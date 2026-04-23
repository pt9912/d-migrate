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
internal data class TableExportParams(
    val pool: ConnectionPool,
    val table: String,
    val filter: dev.dmigrate.core.data.DataFilter?,
    val config: PipelineConfig,
    val writer: DataChunkWriter,
    val counting: CountingOutputStream,
    val reporter: ProgressReporter,
    val ordinal: Int,
    val tableCount: Int,
    val resumeMarker: ResumeMarker?,
    val onChunkProcessed: (TableChunkProgress) -> Unit,
)

internal class TableExporter(private val reader: DataReader) {

    private data class ChunkProgressContext(
        val table: String,
        val onChunkProcessed: (TableChunkProgress) -> Unit,
    )

    fun export(params: TableExportParams): TableExportSummary {
        val pool = params.pool; val table = params.table; val filter = params.filter
        val config = params.config; val writer = params.writer; val counting = params.counting
        val reporter = params.reporter; val ordinal = params.ordinal; val tableCount = params.tableCount
        val resumeMarker = params.resumeMarker; val onChunkProcessed = params.onChunkProcessed
        val chunkProgress = ChunkProgressContext(table, onChunkProcessed)
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
                        val indices = resolveMarkerIndices(resumeMarker, chunk.columns)
                        markerIdx = indices.first
                        tieIdxs = indices.second
                    }
                    writer.write(chunk)
                    rows += chunk.rows.size.toLong()
                    chunks += 1L
                    reporter.report(ProgressEvent.ExportChunkProcessed(
                        table = table, tableOrdinal = ordinal, tableCount = tableCount,
                        chunkIndex = chunks.toInt(), rowsInChunk = chunk.rows.size.toLong(),
                        rowsProcessed = rows, bytesWritten = counting.count - bytesBefore,
                    ))
                    emitChunkProgress(resumeMarker, chunk, markerIdx, tieIdxs, chunkProgress, rows, chunks)
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

    private fun resolveMarkerIndices(
        resumeMarker: ResumeMarker?,
        columns: List<dev.dmigrate.core.data.ColumnDescriptor>,
    ): Pair<Int, IntArray> {
        if (resumeMarker == null) return -1 to IntArray(0)
        val markerIdx = columns.indexOfFirst { it.name.equals(resumeMarker.markerColumn, ignoreCase = true) }
        val tieIdxs = IntArray(resumeMarker.tieBreakerColumns.size) { i ->
            val col = resumeMarker.tieBreakerColumns[i]
            columns.indexOfFirst { it.name.equals(col, ignoreCase = true) }
        }
        return markerIdx to tieIdxs
    }

    private fun emitChunkProgress(
        resumeMarker: ResumeMarker?,
        chunk: dev.dmigrate.core.data.DataChunk,
        markerIdx: Int,
        tieIdxs: IntArray,
        chunkProgress: ChunkProgressContext,
        rows: Long,
        chunks: Long,
    ) {
        if (resumeMarker == null || chunk.rows.isEmpty()) return
        val lastRow = chunk.rows.last()
        val markerValue = if (markerIdx >= 0) lastRow[markerIdx] else null
        val tieValues: List<Any?> = if (tieIdxs.isEmpty()) emptyList()
        else tieIdxs.map { i -> if (i >= 0) lastRow[i] else null }
        runCatching {
            chunkProgress.onChunkProcessed(
                TableChunkProgress(
                    table = chunkProgress.table,
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
