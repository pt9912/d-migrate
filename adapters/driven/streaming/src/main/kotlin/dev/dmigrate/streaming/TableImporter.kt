package dev.dmigrate.streaming

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.DataChunkReaderFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions

internal data class TableImportParams(
    val pool: ConnectionPool,
    val writer: DataWriter,
    val tableInput: ResolvedTableInput,
    val format: DataExportFormat,
    val options: ImportOptions,
    val readOptions: FormatReadOptions = FormatReadOptions(),
    val config: PipelineConfig,
    val reporter: ProgressReporter,
    val ordinal: Int,
    val tableCount: Int,
    val resumeState: ImportTableResumeState?,
    val onChunkCommitted: (ImportChunkCommit) -> Unit,
)

internal class TableImporter(
    private val readerFactory: DataChunkReaderFactory,
    private val onTableOpened: (table: String, targetColumns: List<TargetColumn>) -> Unit,
) {

    fun import(params: TableImportParams): TableImportSummary {
        val tableStartedAt = System.nanoTime()
        var reader: DataChunkReader? = null
        var session: TableImportSession? = null
        var primaryFailure: Throwable? = null
        val state = ImportLoopState()

        val committedChunksOffset: Long = params.resumeState?.committedChunks ?: 0L
        val effectiveOptions = if (committedChunksOffset > 0L) {
            params.options.copy(truncate = false)
        } else {
            params.options
        }
        state.chunksCommittedTotal = committedChunksOffset

        try {
            reader = readerFactory.create(
                format = params.format,
                input = params.tableInput.openInput(),
                table = params.tableInput.table,
                chunkSize = params.config.chunkSize,
                options = params.readOptions,
            )
            session = params.writer.openTable(params.pool, params.tableInput.table, effectiveOptions)
            onTableOpened(params.tableInput.table, session.targetColumns)
            params.reporter.report(
                ProgressEvent.ImportTableStarted(params.tableInput.table, params.ordinal, params.tableCount)
            )
            state.targetColumns = session.targetColumns.map { it.asColumnDescriptor() }

            skipCommittedChunks(reader, committedChunksOffset)

            val firstChunk = reader.nextChunk()
            val bindingPlan = buildBindingPlan(
                table = params.tableInput.table,
                headerColumns = reader.headerColumns(),
                firstChunk = firstChunk,
                targetColumns = session.targetColumns,
            )
            val deserializer = buildDeserializer(session.targetColumns, params.readOptions)
            val chunkContext = ChunkContext(
                table = params.tableInput.table,
                ordinal = params.ordinal,
                tableCount = params.tableCount,
                options = params.options,
                format = params.format,
                bindingPlan = bindingPlan,
                deserializer = deserializer,
            )

            importChunks(reader, session, chunkContext, state, params.reporter, params.onChunkCommitted, firstChunk)

            if (state.error == null) {
                when (val finish = session.finishTable()) {
                    is FinishTableResult.Success -> state.sequenceAdjustments = finish.adjustments
                    is FinishTableResult.PartialFailure -> {
                        state.sequenceAdjustments = finish.adjustments
                        state.partialFinish = finish
                    }
                }
            }
        } catch (throwable: Throwable) {
            primaryFailure = throwable
            throw throwable
        } finally {
            var cleanupFailure: Throwable? = null
            closeAndCollect(reader, primaryFailure) {
                cleanupFailure = cleanupFailure?.apply { addSuppressed(it) } ?: it
            }
            closeAndCollect(session, primaryFailure) {
                cleanupFailure = cleanupFailure?.apply { addSuppressed(it) } ?: it
            }
            if (primaryFailure == null && cleanupFailure != null) primaryFailure = cleanupFailure
        }
        primaryFailure?.let { throw it }

        val tableDurationMs = elapsedMs(tableStartedAt)
        val tableStatus = if (state.error == null && state.partialFinish == null) {
            TableProgressStatus.COMPLETED
        } else {
            TableProgressStatus.FAILED
        }
        params.reporter.report(
            ProgressEvent.ImportTableFinished(
                table = params.tableInput.table,
                tableOrdinal = params.ordinal,
                tableCount = params.tableCount,
                rowsInserted = state.rowsInserted,
                rowsUpdated = state.rowsUpdated,
                rowsSkipped = state.rowsSkipped,
                rowsUnknown = state.rowsUnknown,
                rowsFailed = state.rowsFailed,
                durationMs = tableDurationMs,
                status = tableStatus,
            )
        )

        return TableImportSummary(
            table = params.tableInput.table,
            rowsInserted = state.rowsInserted,
            rowsUpdated = state.rowsUpdated,
            rowsSkipped = state.rowsSkipped,
            rowsUnknown = state.rowsUnknown,
            rowsFailed = state.rowsFailed,
            chunkFailures = state.chunkFailures.toList(),
            sequenceAdjustments = state.sequenceAdjustments,
            targetColumns = state.targetColumns,
            triggerMode = params.options.triggerMode,
            failedFinish = state.partialFinish?.toFailedFinishInfo(),
            error = state.error,
            durationMs = tableDurationMs,
        )
    }

    private fun skipCommittedChunks(reader: DataChunkReader, offset: Long) {
        if (offset <= 0L) return
        var skipped = 0L
        while (skipped < offset) {
            reader.nextChunk() ?: break
            skipped += 1
        }
    }

    private fun closeAndCollect(
        closeable: AutoCloseable?,
        primaryFailure: Throwable?,
        recordCleanupFailure: (Throwable) -> Unit,
    ) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (cleanup: Throwable) {
            if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else recordCleanupFailure(cleanup)
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun FinishTableResult.PartialFailure.toFailedFinishInfo(): FailedFinishInfo {
        val closeCause = cause.suppressedExceptions.firstOrNull()
        return FailedFinishInfo(
            adjustments = adjustments,
            causeMessage = cause.message.orEmpty(),
            causeClass = cause::class.qualifiedName ?: cause.javaClass.name,
            causeStack = cause.stackTrace.takeIf { it.isNotEmpty() }?.let { cause.stackTraceToString() },
            closeCauseMessage = closeCause?.message,
            closeCauseClass = closeCause?.let { it::class.qualifiedName ?: it.javaClass.name },
            closeCauseStack = closeCause?.stackTrace?.takeIf { it.isNotEmpty() }?.let { closeCause.stackTraceToString() },
        )
    }
}
