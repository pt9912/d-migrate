package dev.dmigrate.streaming

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataChunkReaderFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.SeekableDataChunkReaderFactory

/**
 * Pull-basierter Streaming-Importer. Liest Chunks aus einem Reader und
 * schreibt sie ueber den dialektspezifischen Writer chunkweise in die DB.
 *
 * Orchestriert Input-Aufloesung ([ImportInputResolver]), Per-Tabelle-Import
 * ([TableImporter]) und Result-Aggregation.
 *
 * S6 (2026-06-07) / Review-Finding F4: [seekableReaderFactory] ist jetzt
 * Optional (Default `null`). Konsumenten, die keinen seekable Pfad
 * exponieren — heute MCP, viele Tests — koennen den Konstruktor ohne
 * weitere Imports rufen. Der `is ResolvedTableInput.Seekable -> error(...)`-
 * Stopgap in der Loop fasst das `null` ab und produziert eine
 * deterministische Fehlermeldung; S7 verdrahtet den echten Konsum
 * durch den TableImporter, wenn die Factory non-null ist.
 */
class StreamingImporter(
    private val readerFactory: DataChunkReaderFactory,
    @Suppress("UnusedPrivateMember")
    private val seekableReaderFactory: SeekableDataChunkReaderFactory? = null,
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
            // S5a/S5b/S6 (2026-06-07): der ImportInputResolver liefert seit
            // ResolvedBundle/ResolvedSingleFile-Branch potenziell
            // Seekable-Werte. Der seekableReaderFactory-Konstruktor-
            // Parameter ist seit S6 (AP12 §5.1) Pflicht; der Konsum durch
            // TableImporter (Sealed-Sweep + Dispatch) ist explizit S7.
            // Bis S7 ausgeliefert ist, lehnen wir Seekable-Pfade hier
            // hart ab.
            val streamInput = when (tableInput) {
                is ResolvedTableInput.Stream -> tableInput
                is ResolvedTableInput.Seekable -> error(
                    "ResolvedTableInput.Seekable consumption is not yet wired into StreamingImporter; " +
                        "S7 adds the TableImporter dispatch path via seekableReaderFactory."
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
