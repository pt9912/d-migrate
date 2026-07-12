package dev.dmigrate.streaming

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.concurrency.ParallelWorkExecutor
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataChunkReaderFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.SeekableDataChunkReaderFactory
import dev.dmigrate.format.data.ValueDeserializerFactory

/**
 * Pull-basierter Streaming-Importer. Liest Chunks aus einem Reader und
 * schreibt sie ueber den dialektspezifischen Writer chunkweise in die DB.
 *
 * Orchestriert Input-Aufloesung ([ImportInputResolver]), Per-Tabelle-Import
 * ([TableImporter]) und Result-Aggregation.
 *
 * Seekable-Pfad seit S7 (2026-06-08) produktiv via [TableImporter];
 * Pre-Stream-Check unten ist die dritte von vier MCP-Parquet-
 * Isolations-Linien, siehe
 * `docs/adr/0007-mcp-parquet-isolation-defense-in-depth.md`.
 */
class StreamingImporter(
    private val readerFactory: DataChunkReaderFactory,
    private val valueDeserializerFactory: ValueDeserializerFactory,
    private val seekableReaderFactory: SeekableDataChunkReaderFactory? = null,
    private val writerLookup: (DatabaseDialect) -> DataWriter,
    private val onTableOpened: (table: String, targetColumns: List<TargetColumn>) -> Unit = { _, _ -> },
    private val parallelExecutor: ParallelWorkExecutor = ParallelWorkExecutor("import-worker"),
) {

    /** Test seam: cancel-propagation tests swap this with a
     *  capturing override before invoking [import]. Production callers leave
     *  it at the default. */
    internal var tableImporter: TableImporter = TableImporter(
        readerFactory = readerFactory,
        onTableOpened = onTableOpened,
        valueDeserializerFactory = valueDeserializerFactory,
        seekableReaderFactory = seekableReaderFactory,
    )

    fun import(
        pool: ConnectionPool,
        input: ImportInput,
        format: DataExportFormat,
        options: dev.dmigrate.driver.data.ImportOptions = dev.dmigrate.driver.data.ImportOptions(),
        readOptions: FormatReadOptions = FormatReadOptions(),
        config: PipelineConfig = PipelineConfig(),
        progressReporter: ProgressReporter = NoOpProgressReporter,
        operationId: String? = null,
        resuming: Boolean = false,
        skippedTables: Set<String> = emptySet(),
        resumeStateByTable: Map<String, ImportTableResumeState> = emptyMap(),
        onChunkCommitted: (ImportChunkCommit) -> Unit = {},
        onTableCompleted: (TableImportSummary) -> Unit = {},
        cancellationToken: CancellationToken = CancellationToken.none(),
        /**
         * LN-007/LN-008 (ADR 0032): maps the resolved input table names into FK-safe
         * concurrency layers for the parallel path ([PipelineConfig.parallelism] > 1).
         * Default = one layer (the sequential path never consults it).
         */
        fkLayers: (List<String>) -> List<List<String>> = { listOf(it) },
    ): ImportResult {
        val writer = writerLookup(pool.dialect)
        val discoveredInputs = ImportInputResolver().resolve(input, format)
        require(discoveredInputs.isNotEmpty()) {
            "No tables to import from $input"
        }

        cancellationToken.throwIfCancellationRequested()
        progressReporter.report(ProgressEvent.RunStarted(
            operation = ProgressOperation.IMPORT,
            totalTables = discoveredInputs.size,
            operationId = operationId,
            resuming = resuming,
        ))

        val startedAt = System.nanoTime()
        val loop = ImportLoopParams(
            pool, writer, discoveredInputs, format, options, readOptions, config,
            progressReporter, skippedTables, resumeStateByTable, onChunkCommitted,
            onTableCompleted, cancellationToken,
        )
        val summaries = if (config.parallelism > 1) {
            importParallel(loop, fkLayers)
        } else {
            importSequential(loop)
        }

        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        return ImportResult(
            tables = summaries,
            totalRowsInserted = summaries.sumOf { it.rowsInserted },
            totalRowsUpdated = summaries.sumOf { it.rowsUpdated },
            totalRowsSkipped = summaries.sumOf { it.rowsSkipped },
            totalRowsUnknown = summaries.sumOf { it.rowsUnknown },
            totalRowsFailed = summaries.sumOf { it.rowsFailed },
            durationMs = durationMs,
            operationId = operationId,
        )
    }

    private fun importSequential(p: ImportLoopParams): List<TableImportSummary> {
        val summaries = mutableListOf<TableImportSummary>()
        for ((index, tableInput) in p.discoveredInputs.withIndex()) {
            p.cancellationToken.throwIfCancellationRequested()
            if (tableInput.table in p.skippedTables) continue
            checkSeekableWiring(tableInput)
            val summary = tableImporter.import(
                TableImportParams(
                    pool = p.pool, writer = p.writer, tableInput = tableInput, format = p.format,
                    options = p.options, readOptions = p.readOptions, config = p.config,
                    reporter = p.progressReporter, ordinal = index + 1, tableCount = p.discoveredInputs.size,
                    resumeState = p.resumeStateByTable[tableInput.table], onChunkCommitted = p.onChunkCommitted,
                    cancellationToken = p.cancellationToken,
                )
            )
            summaries += summary
            p.cancellationToken.throwIfCancellationRequested()
            p.onTableCompleted(summary)
        }
        return summaries
    }

    /**
     * LN-007/LN-008 (ADR 0032): run each FK layer's inputs through the bounded
     * [parallelExecutor]; layers are a barrier so a table never imports before the
     * tables it references. Summaries are collected and `onTableCompleted` fired on
     * this thread after each layer — callbacks never run on a worker. `--parallel`
     * excludes `--resume`, so no per-chunk checkpoint runs here.
     */
    private fun importParallel(
        p: ImportLoopParams,
        fkLayers: (List<String>) -> List<List<String>>,
    ): List<TableImportSummary> {
        val byTable = p.discoveredInputs.associateBy { it.table }
        val summaries = mutableListOf<TableImportSummary>()
        for (layer in fkLayers(p.discoveredInputs.map { it.table })) {
            p.cancellationToken.throwIfCancellationRequested()
            val inputs = layer.mapNotNull { byTable[it] }.filter { it.table !in p.skippedTables }
            val results = parallelExecutor.run(
                inputs.map { input -> { importOne(p, input) } },
                p.config.parallelism,
                p.cancellationToken,
            )
            for (summary in results) {
                summaries += summary
                p.cancellationToken.throwIfCancellationRequested()
                p.onTableCompleted(summary)
            }
        }
        return summaries
    }

    private fun importOne(p: ImportLoopParams, tableInput: ResolvedTableInput): TableImportSummary {
        checkSeekableWiring(tableInput)
        return tableImporter.import(
            TableImportParams(
                pool = p.pool, writer = p.writer, tableInput = tableInput, format = p.format,
                options = p.options, readOptions = p.readOptions, config = p.config,
                reporter = NoOpProgressReporter, ordinal = 1, tableCount = p.discoveredInputs.size,
                resumeState = null, onChunkCommitted = {}, cancellationToken = p.cancellationToken,
            )
        )
    }

    private fun checkSeekableWiring(tableInput: ResolvedTableInput) {
        // Pre-Stream-Check (ADR-0007 Linie 3 / ADR-0006 Exception-Familie):
        // null-Factory + Seekable-Input → Wiring-Drift, fail-fast.
        if (tableInput is ResolvedTableInput.Seekable && seekableReaderFactory == null) {
            error(
                "Seekable input requires seekableReaderFactory; " +
                    "consumer should not produce ResolvedTableInput.Seekable " +
                    "without wiring it (got Seekable input for table '${tableInput.table}')."
            )
        }
    }

    private data class ImportLoopParams(
        val pool: ConnectionPool,
        val writer: DataWriter,
        val discoveredInputs: List<ResolvedTableInput>,
        val format: DataExportFormat,
        val options: dev.dmigrate.driver.data.ImportOptions,
        val readOptions: FormatReadOptions,
        val config: PipelineConfig,
        val progressReporter: ProgressReporter,
        val skippedTables: Set<String>,
        val resumeStateByTable: Map<String, ImportTableResumeState>,
        val onChunkCommitted: (ImportChunkCommit) -> Unit,
        val onTableCompleted: (TableImportSummary) -> Unit,
        val cancellationToken: CancellationToken,
    )
}
