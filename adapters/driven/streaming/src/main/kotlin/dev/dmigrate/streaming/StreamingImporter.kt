package dev.dmigrate.streaming

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataChunkReaderFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions

/**
 * Pull-basierter Streaming-Importer. Liest Chunks aus einem Reader und
 * schreibt sie ueber den dialektspezifischen Writer chunkweise in die DB.
 *
 * Orchestriert Input-Aufloesung ([ImportInputResolver]), Per-Tabelle-Import
 * ([TableImporter]) und Result-Aggregation.
 */
class StreamingImporter(
    private val readerFactory: DataChunkReaderFactory,
    private val writerLookup: (DatabaseDialect) -> DataWriter,
    private val onTableOpened: (table: String, targetColumns: List<TargetColumn>) -> Unit = { _, _ -> },
) {

    /** Test seam: cancel-propagation tests swap this with a
     *  capturing override before invoking [import]. Production callers leave
     *  it at the default. */
    internal var tableImporter: TableImporter = TableImporter(readerFactory, onTableOpened)

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
    ): ImportResult {
        val writer = writerLookup(pool.dialect)
        val inputResolver = ImportInputResolver()

        val discoveredInputs = inputResolver.resolve(input, format)
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
        val summaries = mutableListOf<TableImportSummary>()

        for ((index, tableInput) in discoveredInputs.withIndex()) {
            cancellationToken.throwIfCancellationRequested()
            if (tableInput.table in skippedTables) continue
            // S5a (2026-06-06): der ImportInputResolver liefert seit
            // ResolvedBundle-Branch potenziell Seekable-Werte.
            // End-to-End-Konsum durch TableImporter ist nach Umbrella §3
            // explizit S7-Arbeit (`ParquetSeekableDataChunkReaderFactory`-
            // Wiring im StreamingImporter-Constructor). Solange S7 nicht
            // ausgeliefert ist, lehnen wir Seekable-Pfade hier hart ab —
            // die Aussage ist nicht "kommt in Sub-Slice X", sondern
            // "der Streaming-Layer braucht einen seekableReaderFactory-
            // Pflichtparameter, der noch nicht im Constructor steht".
            val streamInput = when (tableInput) {
                is ResolvedTableInput.Stream -> tableInput
                is ResolvedTableInput.Seekable -> error(
                    "ResolvedTableInput.Seekable consumption is not yet wired into StreamingImporter; " +
                        "S7 adds the seekableReaderFactory constructor parameter and the dispatch path."
                )
            }
            val summary = tableImporter.import(TableImportParams(
                pool = pool,
                writer = writer,
                tableInput = streamInput,
                format = format,
                options = options,
                readOptions = readOptions,
                config = config,
                reporter = progressReporter,
                ordinal = index + 1,
                tableCount = discoveredInputs.size,
                resumeState = resumeStateByTable[tableInput.table],
                onChunkCommitted = onChunkCommitted,
                cancellationToken = cancellationToken,
            ))
            summaries += summary
            cancellationToken.throwIfCancellationRequested()
            onTableCompleted(summary)
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
}
