package dev.dmigrate.streaming

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Pull-based streaming exporter. Reads tables from a DB connection and writes
 * them to one or more output streams (see [ExportOutput]).
 *
 * - Receives a [ConnectionPool] (no connection ownership in the caller — Reader
 *   and TableLister borrow a connection per table)
 * - Iterates over tables and obtains a fresh [DataChunkWriter] from the
 *   [DataChunkWriterFactory] for each
 * - Empty-table guarantee: every Reader yields at least one chunk; the Writer
 *   writes no data rows for `chunk.rows.isEmpty()`, but `begin`/`end` are still
 *   called — so CSV gets its header, JSON `[]`, and YAML `[]`
 * - Closes every Writer and ChunkSequence cleanly, even on exceptions, so no
 *   connections leak back into the pool
 */
class StreamingExporter(
    private val reader: DataReader,
    private val tableLister: TableLister,
    private val writerFactory: DataChunkWriterFactory,
) {

    /**
     * Exports the given tables to the output sink.
     *
     * @param pool Connection pool — Reader and TableLister borrow connections per call.
     * @param tables Table names, or `emptyList()` to auto-discover via [TableLister.listTables].
     * @param output Where to write (Stdout, SingleFile, FilePerTable).
     * @param format Output format (json/yaml/csv).
     * @param options Format-specific options (encoding, BOM, CSV delimiter, ...).
     * @param config Pipeline configuration (chunkSize).
     * @param filter Optional filter applied to all tables.
     * @return Aggregate statistics across all tables.
     */
    fun export(
        pool: ConnectionPool,
        tables: List<String>,
        output: ExportOutput,
        format: DataExportFormat,
        options: ExportOptions = ExportOptions(),
        config: PipelineConfig = PipelineConfig(),
        filter: DataFilter? = null,
        progressReporter: ProgressReporter = NoOpProgressReporter,
        /** Stable operation ID for the run. Runners generate a UUID and pass it here;
         *  it appears in [ProgressEvent.RunStarted.operationId] and [ExportResult.operationId]
         *  so that manifest, stderr summary, and logs reference the same run. Default `null`
         *  for call-sites that do not need an ID (tests). */
        operationId: String? = null,
        /** `true` when resuming from an existing checkpoint manifest; `false` for a fresh run.
         *  The `ProgressRenderer` uses this flag for the "Starting run ..." vs "Resuming run ..." label. */
        resuming: Boolean = false,
        /** Tables to skip in this run — on resume, the tables already marked `COMPLETED`
         *  in the manifest. Empty for fresh runs. */
        skippedTables: Set<String> = emptySet(),
        /** Callback invoked after each completed table export — including empty tables
         *  and error cases (`error != null`). The Runner uses it to update the checkpoint manifest. */
        onTableCompleted: (TableExportSummary) -> Unit = {},
        /**
         * Per-table optional [ResumeMarker] for mid-table resume.
         *
         * - **Missing entry** -> legacy path: `streamTable(pool, table, filter, chunkSize)`
         *   without marker-based ordering.
         * - **Position == null** (fresh track) -> the Reader enforces `ORDER BY
         *   (markerColumn, tieBreakers...)` without a WHERE cascade, so a later resume
         *   can reproduce the same ordering.
         * - **Position != null** (resume from position) -> strict `>`-cascade filter;
         *   the exporter starts from the documented composite marker.
         */
        resumeMarkers: Map<String, ResumeMarker> = emptyMap(),
        /**
         * Chunk-granular progress callback for tables streamed with a [ResumeMarker].
         * Invoked per successfully written, non-empty chunk — empty chunks (empty-table
         * contract) do **not** trigger this callback, so the Runner never persists a
         * position with `null` values.
         *
         * The Runner uses this signal to update the manifest with the new position tuple
         * after each chunk. Write errors in the callback should **not** abort the export.
         */
        onChunkProcessed: (TableChunkProgress) -> Unit = {},
    ): ExportResult {
        val discoveredTables = tables.ifEmpty { tableLister.listTables(pool) }
        // Skipped tables are not exported but count toward the total table count
        // and appear as pre-confirmed summaries in [ExportResult] for stable
        // progress reporting and manifest state consistency.
        val effectiveTables = discoveredTables.filter { it !in skippedTables }
        require(discoveredTables.isNotEmpty()) {
            "No tables to export — neither --tables given nor any tables found via TableLister."
        }

        progressReporter.report(ProgressEvent.RunStarted(
            operation = ProgressOperation.EXPORT,
            totalTables = discoveredTables.size,
            operationId = operationId,
            resuming = resuming,
        ))

        val startedAt = System.nanoTime()
        val tableExporter = TableExporter(reader)
        val tableSummaries = mutableListOf<TableExportSummary>()
        var totalBytes = 0L

        when (output) {
            is ExportOutput.Stdout -> {
                require(discoveredTables.size == 1) {
                    "Stdout output supports exactly one table, got ${discoveredTables.size}"
                }
                if (effectiveTables.isNotEmpty()) {
                    val table = effectiveTables.single()
                    val nonClosing = NonClosingOutputStream(System.out)
                    val counting = CountingOutputStream(nonClosing)
                    val writer = writerFactory.create(format, counting, options)
                    try {
                        val summary = tableExporter.export(
                            pool, table, filter, config, writer, counting,
                            progressReporter, 1, 1,
                            resumeMarkers[table], onChunkProcessed,
                        )
                        tableSummaries += summary
                        onTableCompleted(summary)
                    } finally {
                        runCatching { writer.close() }
                        runCatching { System.out.flush() }
                    }
                    totalBytes += counting.count
                }
            }

            is ExportOutput.SingleFile -> {
                require(discoveredTables.size == 1) {
                    "SingleFile output supports exactly one table, got ${discoveredTables.size}"
                }
                if (effectiveTables.isNotEmpty()) {
                    val table = effectiveTables.single()
                    exportToFile(output.path, format, options) { counting, writer ->
                        val summary = tableExporter.export(
                            pool, table, filter, config, writer, counting,
                            progressReporter, 1, 1,
                            resumeMarkers[table], onChunkProcessed,
                        )
                        tableSummaries += summary
                        onTableCompleted(summary)
                        totalBytes += counting.count
                    }
                }
            }

            is ExportOutput.FilePerTable -> {
                Files.createDirectories(output.directory)
                val activeCount = discoveredTables.size
                for ((index, table) in discoveredTables.withIndex()) {
                    if (table in skippedTables) continue
                    val path = output.directory.resolve(ExportOutput.fileNameFor(table, format))
                    exportToFile(path, format, options) { counting, writer ->
                        val summary = tableExporter.export(
                            pool, table, filter, config, writer, counting,
                            progressReporter, index + 1, activeCount,
                            resumeMarkers[table], onChunkProcessed,
                        )
                        tableSummaries += summary
                        onTableCompleted(summary)
                        totalBytes += counting.count
                    }
                }
            }
        }

        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        return ExportResult(
            tables = tableSummaries,
            totalRows = tableSummaries.sumOf { it.rows },
            totalChunks = tableSummaries.sumOf { it.chunks },
            totalBytes = totalBytes,
            durationMs = durationMs,
            operationId = operationId,
        )
    }

    private inline fun exportToFile(
        path: Path,
        format: DataExportFormat,
        options: ExportOptions,
        block: (CountingOutputStream, DataChunkWriter) -> Unit,
    ) {
        Files.newOutputStream(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { fileOut ->
            BufferedOutputStream(fileOut).use { buffered ->
                val counting = CountingOutputStream(buffered)
                val writer = writerFactory.create(format, counting, options)
                try {
                    block(counting, writer)
                } finally {
                    runCatching { writer.close() }
                }
            }
        }
    }
}


/**
 * OutputStream wrapper that counts bytes written through it.
 * Used for [ExportResult.totalBytes] statistics. `close()` is forwarded
 * to the delegate — for Stdout the [NonClosingOutputStream] wrapper
 * prevents `System.out` from being closed.
 */
internal class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len.toLong()
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}

/**
 * OutputStream wrapper that turns `close()` into a no-op. `flush()` and
 * `write()` are forwarded. Used in [StreamingExporter] for the Stdout
 * branch so that [DataChunkWriter.close] (which closes its underlying
 * stream per contract) does not destroy `System.out`.
 */
internal class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()
    override fun close() {
        // intentional no-op — flush() is called explicitly in StreamingExporter
    }
}
