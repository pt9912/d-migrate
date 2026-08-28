package dev.dmigrate.streaming

import dev.dmigrate.core.concurrency.ParallelWorkExecutor
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.ResumeMarker
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.BundleClosureContext
import dev.dmigrate.format.data.BundleClosureTable
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
    private val parallelExecutor: ParallelWorkExecutor = ParallelWorkExecutor("export-worker"),
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
        warningSink: (String) -> Unit = {},
        /**
         * Bundle-Closure-Hook (Parquet Cut A S3b, AP7 §10.1).
         * Wird ausschliesslich am Ende einer
         * [ExportOutput.FilePerTable]-Operation aufgerufen — nach
         * dem Schliessen aller Pro-Tabelle-Writer und vor der
         * Rueckgabe des [ExportResult]. Default: kein Hook.
         *
         * Format-Adapter (heute nur Parquet) verdrahten hier ihren
         * Manifest-Writer; JSON/YAML/CSV-Pfade lassen den Default
         * stehen — fuer sie gibt es kein Bundle-Konzept.
         */
        onBundleClosure: (BundleClosureContext) -> Unit = {},
        /** LN-008 (ADR 0032): parent → child partitions; fans out per child on the parallel path. */
        partitionChildren: Map<String, List<String>> = emptyMap(),
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
                        val result = tableExporter.export(TableExportParams(
                            pool, table, filter, config, writer, counting,
                            progressReporter, 1, 1,
                            resumeMarkers[table], onChunkProcessed, warningSink,
                        ))
                        tableSummaries += result.summary
                        onTableCompleted(result.summary)
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
                        val result = tableExporter.export(TableExportParams(
                            pool, table, filter, config, writer, counting,
                            progressReporter, 1, 1,
                            resumeMarkers[table], onChunkProcessed, warningSink,
                        ))
                        tableSummaries += result.summary
                        onTableCompleted(result.summary)
                        totalBytes += counting.count
                    }
                }
            }

            is ExportOutput.FilePerTable -> {
                totalBytes += dispatchFilePerTable(
                    FilePerTableParams(
                        output, discoveredTables, skippedTables, format, options, pool, filter, config,
                        progressReporter, resumeMarkers, onChunkProcessed, onTableCompleted, warningSink,
                        onBundleClosure, partitionChildren, tableExporter, tableSummaries,
                    )
                )
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

    /** Routes FilePerTable to the sequential or the parallel path (LN-007/LN-008, ADR 0032). */
    private fun dispatchFilePerTable(params: FilePerTableParams): Long =
        if (params.config.parallelism > 1) {
            exportFilePerTableParallel(params.toParallel())
        } else {
            exportFilePerTable(params)
        }

    private fun exportFilePerTable(params: FilePerTableParams): Long {
        val output = params.output
        val discoveredTables = params.discoveredTables
        val skippedTables = params.skippedTables
        val format = params.format
        val options = params.options
        val pool = params.pool
        val filter = params.filter
        val config = params.config
        val progressReporter = params.progressReporter
        val resumeMarkers = params.resumeMarkers
        val onChunkProcessed = params.onChunkProcessed
        val onTableCompleted = params.onTableCompleted
        val warningSink = params.warningSink
        val onBundleClosure = params.onBundleClosure
        val tableExporter = params.tableExporter
        val tableSummaries = params.tableSummaries
        Files.createDirectories(output.directory)
        // Untrusted table names from the source catalog must not escape the
        // output directory (CWE-22). Validate all up front so the export fails
        // loudly before any file is written, not after a partial run.
        val safePaths = discoveredTables.filterNot { it in skippedTables }
            .associateWith { ExportOutput.resolveFileFor(output.directory, it, format) }
        val activeCount = discoveredTables.size
        val bundleClosureTables = mutableListOf<BundleClosureTable>()
        var totalBytes = 0L
        for ((index, table) in discoveredTables.withIndex()) {
            if (table in skippedTables) continue
            val path = safePaths.getValue(table)
            exportToFile(path, format, options) { counting, writer ->
                val result = tableExporter.export(TableExportParams(
                    pool, table, filter, config, writer, counting,
                    progressReporter, index + 1, activeCount,
                    resumeMarkers[table], onChunkProcessed, warningSink,
                ))
                tableSummaries += result.summary
                onTableCompleted(result.summary)
                totalBytes += counting.count
                bundleClosureTables += BundleClosureTable(
                    table = result.summary.table,
                    file = path,
                    schema = result.schema,
                    rowCount = result.summary.rows,
                )
            }
        }
        if (bundleClosureTables.isNotEmpty()) {
            onBundleClosure(
                BundleClosureContext(
                    directory = output.directory,
                    format = format,
                    tables = bundleClosureTables,
                )
            )
        }
        return totalBytes
    }

    /**
     * LN-007/LN-008 (ADR 0032): parallel FilePerTable export. Each listed table
     * (fanned out into one unit per child partition when [partitionChildren] carries
     * it) writes to its own file through the bounded [parallelExecutor]. Results are
     * aggregated on this thread **after** the parallel run, so progress/manifest
     * callbacks never fire from a worker thread. `--parallel` excludes `--resume`,
     * so mid-table markers/chunk-progress are not in play here.
     */
    private fun exportFilePerTableParallel(params: FilePerTableParallelParams): Long {
        Files.createDirectories(params.output.directory)
        val active = params.discoveredTables.filter { it !in params.skippedTables }
        val unitTables: List<String> = active.flatMap { parent ->
            params.partitionChildren[parent]?.takeIf { it.isNotEmpty() } ?: listOf(parent)
        }
        // Same CWE-22 guard as the sequential path: reject any escaping table
        // name on this thread before dispatching workers (see exportOneFile).
        unitTables.forEach { ExportOutput.resolveFileFor(params.output.directory, it, params.format) }
        val results = parallelExecutor.run(
            unitTables.map { table -> { exportOneFile(params, table) } },
            params.config.parallelism,
        )
        val bundleTables = mutableListOf<BundleClosureTable>()
        var totalBytes = 0L
        for (result in results) {
            params.tableSummaries += result.summary
            params.onTableCompleted(result.summary)
            totalBytes += result.bytes
            bundleTables += result.bundleTable
        }
        if (bundleTables.isNotEmpty()) {
            params.onBundleClosure(BundleClosureContext(params.output.directory, params.format, bundleTables))
        }
        return totalBytes
    }

    private fun exportOneFile(params: FilePerTableParallelParams, table: String): ExportUnitResult {
        val path = ExportOutput.resolveFileFor(params.output.directory, table, params.format)
        var captured: ExportUnitResult? = null
        exportToFile(path, params.format, params.options) { counting, writer ->
            val exported = params.tableExporter.export(
                TableExportParams(
                    params.pool, table, params.filter, params.config, writer, counting,
                    NoOpProgressReporter, 1, 1, null, {}, params.warningSink,
                )
            )
            captured = ExportUnitResult(
                exported.summary,
                counting.count,
                BundleClosureTable(exported.summary.table, path, exported.schema, exported.summary.rows),
            )
        }
        return captured!!
    }

    /**
     * Die Parameterform des FilePerTable-Wegs.
     *
     * `FilePerTableParallelParams` gab es fuer den parallelen Zweig laengst; der
     * sequenzielle trug dieselben Werte weiter als Einzelparameter — siebzehn
     * Stueck, in derselben Reihenfolge zweimal ausgeschrieben. Eine gemeinsame
     * Form macht daraus einen Wert, der durchgereicht wird.
     */
    private data class FilePerTableParams(
        val output: ExportOutput.FilePerTable,
        val discoveredTables: List<String>,
        val skippedTables: Set<String>,
        val format: DataExportFormat,
        val options: ExportOptions,
        val pool: ConnectionPool,
        val filter: DataFilter?,
        val config: PipelineConfig,
        val progressReporter: ProgressReporter,
        val resumeMarkers: Map<String, ResumeMarker>,
        val onChunkProcessed: (TableChunkProgress) -> Unit,
        val onTableCompleted: (TableExportSummary) -> Unit,
        val warningSink: (String) -> Unit,
        val onBundleClosure: (BundleClosureContext) -> Unit,
        val partitionChildren: Map<String, List<String>>,
        val tableExporter: TableExporter,
        val tableSummaries: MutableList<TableExportSummary>,
    ) {
        fun toParallel() = FilePerTableParallelParams(
            output, discoveredTables, skippedTables, format, options, pool, filter,
            config, warningSink, onTableCompleted, onBundleClosure, partitionChildren,
            tableExporter, tableSummaries,
        )
    }

    private data class FilePerTableParallelParams(
        val output: ExportOutput.FilePerTable,
        val discoveredTables: List<String>,
        val skippedTables: Set<String>,
        val format: DataExportFormat,
        val options: ExportOptions,
        val pool: ConnectionPool,
        val filter: DataFilter?,
        val config: PipelineConfig,
        val warningSink: (String) -> Unit,
        val onTableCompleted: (TableExportSummary) -> Unit,
        val onBundleClosure: (BundleClosureContext) -> Unit,
        val partitionChildren: Map<String, List<String>>,
        val tableExporter: TableExporter,
        val tableSummaries: MutableList<TableExportSummary>,
    )

    private data class ExportUnitResult(
        val summary: TableExportSummary,
        val bytes: Long,
        val bundleTable: BundleClosureTable,
    )

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
