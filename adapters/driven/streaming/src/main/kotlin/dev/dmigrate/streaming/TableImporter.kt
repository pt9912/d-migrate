package dev.dmigrate.streaming

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnError
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.DataChunkReaderFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.JdbcTypeHint
import dev.dmigrate.format.data.ValueDeserializer

/**
 * Imports a single table by streaming chunks from [DataChunkReader]
 * through normalization and deserialization into a [TableImportSession].
 *
 * Handles resume offset (skip committed chunks), binding plan
 * construction, chunk error recovery, and finish/close lifecycle.
 */
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
        val pool = params.pool; val writer = params.writer; val tableInput = params.tableInput
        val format = params.format; val options = params.options; val readOptions = params.readOptions
        val config = params.config; val reporter = params.reporter; val ordinal = params.ordinal
        val tableCount = params.tableCount; val resumeState = params.resumeState
        val onChunkCommitted = params.onChunkCommitted
        val tableStartedAt = System.nanoTime()
        var reader: DataChunkReader? = null
        var session: TableImportSession? = null
        var primaryFailure: Throwable? = null
        val state = ImportLoopState()

        val committedChunksOffset: Long = resumeState?.committedChunks ?: 0L
        val effectiveOptions = if (committedChunksOffset > 0L) options.copy(truncate = false) else options
        state.chunksCommittedTotal = committedChunksOffset

        try {
            reader = readerFactory.create(
                format = format, input = tableInput.openInput(), table = tableInput.table,
                chunkSize = config.chunkSize, options = readOptions,
            )
            session = writer.openTable(pool, tableInput.table, effectiveOptions)
            onTableOpened(tableInput.table, session.targetColumns)
            reporter.report(ProgressEvent.ImportTableStarted(tableInput.table, ordinal, tableCount))
            state.targetColumns = session.targetColumns.map { it.asColumnDescriptor() }

            skipCommittedChunks(reader, committedChunksOffset)

            val firstChunk = reader.nextChunk()
            val bindingPlan = buildBindingPlan(
                table = tableInput.table, headerColumns = reader.headerColumns(),
                firstChunk = firstChunk, targetColumns = session.targetColumns,
            )
            val deserializer = buildDeserializer(session.targetColumns, readOptions)
            val chunkCtx = ChunkContext(tableInput.table, ordinal, tableCount, options, format, bindingPlan, deserializer)

            importChunks(reader, session, chunkCtx, state, reporter, onChunkCommitted, firstChunk)

            if (state.error == null) {
                when (val finish = session.finishTable()) {
                    is FinishTableResult.Success -> state.sequenceAdjustments = finish.adjustments
                    is FinishTableResult.PartialFailure -> { state.sequenceAdjustments = finish.adjustments; state.partialFinish = finish }
                }
            }
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            var cleanupFailure: Throwable? = null
            closeAndCollect(reader, primaryFailure) { cleanupFailure = cleanupFailure?.apply { addSuppressed(it) } ?: it }
            closeAndCollect(session, primaryFailure) { cleanupFailure = cleanupFailure?.apply { addSuppressed(it) } ?: it }
            if (primaryFailure == null && cleanupFailure != null) primaryFailure = cleanupFailure
        }
        primaryFailure?.let { throw it }

        val tableDurationMs = elapsedMs(tableStartedAt)
        val tableStatus = if (state.error == null && state.partialFinish == null) {
            TableProgressStatus.COMPLETED
        } else {
            TableProgressStatus.FAILED
        }
        reporter.report(ProgressEvent.ImportTableFinished(
            table = tableInput.table, tableOrdinal = ordinal, tableCount = tableCount,
            rowsInserted = state.rowsInserted, rowsUpdated = state.rowsUpdated,
            rowsSkipped = state.rowsSkipped, rowsUnknown = state.rowsUnknown, rowsFailed = state.rowsFailed,
            durationMs = tableDurationMs, status = tableStatus,
        ))

        return TableImportSummary(
            table = tableInput.table,
            rowsInserted = state.rowsInserted, rowsUpdated = state.rowsUpdated,
            rowsSkipped = state.rowsSkipped, rowsUnknown = state.rowsUnknown, rowsFailed = state.rowsFailed,
            chunkFailures = state.chunkFailures.toList(),
            sequenceAdjustments = state.sequenceAdjustments,
            targetColumns = state.targetColumns,
            triggerMode = options.triggerMode,
            failedFinish = state.partialFinish?.toFailedFinishInfo(),
            error = state.error, durationMs = tableDurationMs,
        )
    }

    private fun skipCommittedChunks(reader: DataChunkReader, offset: Long) {
        if (offset <= 0L) return
        var skipped = 0L
        while (skipped < offset) { reader.nextChunk() ?: break; skipped += 1 }
    }

    private class ImportLoopState {
        var rowsInserted = 0L
        var rowsUpdated = 0L
        var rowsSkipped = 0L
        var rowsUnknown = 0L
        var rowsFailed = 0L
        val chunkFailures = mutableListOf<ChunkFailure>()
        var sequenceAdjustments = emptyList<dev.dmigrate.driver.data.SequenceAdjustment>()
        var partialFinish: FinishTableResult.PartialFailure? = null
        var error: String? = null
        var targetColumns = emptyList<ColumnDescriptor>()
        var chunksCommittedTotal = 0L
        val totalRows get() = rowsInserted + rowsUpdated + rowsSkipped + rowsUnknown + rowsFailed
    }

    private class ChunkContext(
        val table: String, val ordinal: Int, val tableCount: Int,
        val options: ImportOptions, val format: DataExportFormat,
        val bindingPlan: BindingPlan, val deserializer: ValueDeserializer,
    )

    private fun importChunks(
        reader: DataChunkReader, session: TableImportSession, ctx: ChunkContext,
        state: ImportLoopState, reporter: ProgressReporter,
        onChunkCommitted: (ImportChunkCommit) -> Unit, firstChunk: DataChunk?,
    ) {
        var nextChunk: DataChunk? = firstChunk
        while (nextChunk != null) {
            val normalizedChunk = try {
                normalizeChunk(nextChunk, ctx.table, ctx.bindingPlan, ctx.deserializer, ctx.format == DataExportFormat.CSV)
            } catch (t: Throwable) {
                val decision = handleNormFailure(nextChunk, t, ctx, state, reporter)
                if (decision == LoopAction.ABORT) throw t
                nextChunk = advanceOrBreak(reader, ctx, state, nextChunk.chunkIndex + 1) ?: break; continue
            }

            val writeResult = try {
                session.write(normalizedChunk)
            } catch (t: Throwable) {
                val action = handleWriteFailure(session, normalizedChunk, t, ctx, state, reporter)
                if (action == LoopAction.ABORT) throw t
                if (action == LoopAction.BREAK) break
                nextChunk = advanceOrBreak(reader, ctx, state, normalizedChunk.chunkIndex + 1) ?: break; continue
            }

            try {
                session.commitChunk()
                state.rowsInserted += writeResult.rowsInserted
                state.rowsUpdated += writeResult.rowsUpdated
                state.rowsSkipped += writeResult.rowsSkipped
                state.rowsUnknown += writeResult.rowsUnknown
                reportChunkProcessed(reporter, ctx, normalizedChunk, state)
                state.chunksCommittedTotal += 1
                runCatching {
                    onChunkCommitted(ImportChunkCommit(
                        table = ctx.table, chunkIndex = normalizedChunk.chunkIndex,
                        chunksCommitted = state.chunksCommittedTotal,
                        rowsInsertedTotal = state.rowsInserted, rowsUpdatedTotal = state.rowsUpdated,
                        rowsSkippedTotal = state.rowsSkipped, rowsUnknownTotal = state.rowsUnknown,
                        rowsFailedTotal = state.rowsFailed,
                    ))
                }
            } catch (t: Throwable) {
                state.rowsFailed += normalizedChunk.rows.size.toLong()
                when (handleChunkFailure(normalizedChunk, t, ctx.options, state.chunkFailures)) {
                    ChunkDecision.ABORT -> throw t
                    ChunkDecision.CONTINUE -> { state.error = t.message ?: t::class.simpleName; break }
                }
            }

            nextChunk = advanceOrBreak(reader, ctx, state, normalizedChunk.chunkIndex + 1) ?: break
        }
    }

    private enum class LoopAction { ABORT, BREAK, CONTINUE }

    private fun handleNormFailure(
        chunk: DataChunk, t: Throwable, ctx: ChunkContext, state: ImportLoopState, reporter: ProgressReporter,
    ): LoopAction {
        return when (handleChunkFailure(chunk, t, ctx.options, state.chunkFailures)) {
            ChunkDecision.ABORT -> LoopAction.ABORT
            ChunkDecision.CONTINUE -> {
                state.rowsFailed += chunk.rows.size.toLong()
                reportChunkProcessed(reporter, ctx, chunk, state)
                LoopAction.CONTINUE
            }
        }
    }

    private fun handleWriteFailure(
        session: TableImportSession, chunk: DataChunk, t: Throwable,
        ctx: ChunkContext, state: ImportLoopState, reporter: ProgressReporter,
    ): LoopAction {
        val recovered = runCatching { session.rollbackChunk() }.isSuccess
        state.rowsFailed += chunk.rows.size.toLong()
        return when (handleChunkFailure(chunk, t, ctx.options, state.chunkFailures)) {
            ChunkDecision.ABORT -> LoopAction.ABORT
            ChunkDecision.CONTINUE -> {
                reportChunkProcessed(reporter, ctx, chunk, state)
                if (!recovered) { state.error = t.message ?: t::class.simpleName; LoopAction.BREAK }
                else LoopAction.CONTINUE
            }
        }
    }

    private fun reportChunkProcessed(
        reporter: ProgressReporter, ctx: ChunkContext, chunk: DataChunk, state: ImportLoopState,
    ) {
        reporter.report(ProgressEvent.ImportChunkProcessed(
            table = ctx.table, tableOrdinal = ctx.ordinal, tableCount = ctx.tableCount,
            chunkIndex = chunk.chunkIndex.toInt() + 1, rowsInChunk = chunk.rows.size.toLong(),
            rowsProcessed = state.totalRows,
            rowsInserted = state.rowsInserted, rowsUpdated = state.rowsUpdated,
            rowsSkipped = state.rowsSkipped, rowsUnknown = state.rowsUnknown, rowsFailed = state.rowsFailed,
        ))
    }

    private fun advanceOrBreak(
        reader: DataChunkReader, ctx: ChunkContext, state: ImportLoopState, nextIdx: Long,
    ): DataChunk? {
        return when (val readResult = tryReadNextChunk(reader, ctx.table, nextIdx, ctx.options, state.chunkFailures)) {
            is ReadNextChunkResult.Chunk -> readResult.chunk
            is ReadNextChunkResult.EndOfInput -> null
            is ReadNextChunkResult.Failed -> { state.error = readResult.reason; null }
        }
    }

    // ── chunk processing helpers ───────────────────

    private fun buildBindingPlan(
        table: String, headerColumns: List<String>?, firstChunk: DataChunk?, targetColumns: List<TargetColumn>,
    ): BindingPlan {
        if (headerColumns == null) {
            return BindingPlan(null, targetColumns, targetColumns.indices.toList(), positional = true)
        }
        val duplicates = headerColumns.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw ImportSchemaMismatchException(
            "Header for table '$table' contains duplicate columns: ${duplicates.joinToString()}")
        if (firstChunk != null && firstChunk.columns.isNotEmpty()) {
            val chunkColumns = firstChunk.columns.map { it.name }
            if (chunkColumns != headerColumns) throw ImportSchemaMismatchException(
                "Header/first chunk mismatch for table '$table': expected $headerColumns, got $chunkColumns")
        }
        val targetByName = targetColumns.associateBy { it.name }
        val unknown = headerColumns.filterNot(targetByName::containsKey)
        if (unknown.isNotEmpty()) throw ImportSchemaMismatchException(
            "Target table '$table' has no columns ${unknown.joinToString()}")
        val sourceIndexByName = headerColumns.withIndex().associate { it.value to it.index }
        val boundColumns = targetColumns.filter { sourceIndexByName.containsKey(it.name) }
        val sourceIndexes = boundColumns.map { sourceIndexByName.getValue(it.name) }
        return BindingPlan(headerColumns, boundColumns, sourceIndexes, positional = false)
    }

    private fun normalizeChunk(
        chunk: DataChunk, table: String, bindingPlan: BindingPlan,
        deserializer: ValueDeserializer, isCsvSource: Boolean,
    ): DataChunk {
        if (!bindingPlan.positional && bindingPlan.sourceHeader != null) {
            val currentHeader = chunk.columns.map { it.name }
            if (currentHeader.isNotEmpty() && currentHeader != bindingPlan.sourceHeader) {
                throw ImportSchemaMismatchException(
                    "All chunks for table '$table' must use the same header order; " +
                        "expected ${bindingPlan.sourceHeader}, got $currentHeader")
            }
        }
        val normalizedRows = chunk.rows.mapIndexed { rowIndex, row ->
            if (bindingPlan.positional) {
                if (row.size != bindingPlan.boundTargetColumns.size) throw ImportSchemaMismatchException(
                    "Chunk row $rowIndex for table '$table' has ${row.size} values, expected ${bindingPlan.boundTargetColumns.size}")
            } else if (bindingPlan.sourceHeader != null && row.size != bindingPlan.sourceHeader.size) {
                throw ImportSchemaMismatchException(
                    "Chunk row $rowIndex for table '$table' has ${row.size} values, expected ${bindingPlan.sourceHeader.size}")
            }
            Array(bindingPlan.boundTargetColumns.size) { idx ->
                val targetColumn = bindingPlan.boundTargetColumns[idx]
                val sourceValue = row[bindingPlan.sourceIndexes[idx]]
                deserializer.deserialize(table, targetColumn.name, sourceValue, isCsvSource)
            }
        }
        return DataChunk(chunk.table, bindingPlan.boundTargetColumns.map { it.asColumnDescriptor() }, normalizedRows, chunk.chunkIndex)
    }

    private fun buildDeserializer(targetColumns: List<TargetColumn>, readOptions: FormatReadOptions): ValueDeserializer {
        val hints = targetColumns.associate { it.name to JdbcTypeHint(it.jdbcType, it.sqlTypeName) }
        return ValueDeserializer(typeHintOf = { hints[it] }, csvNullString = readOptions.csvNullString)
    }

    // ── error handling helpers ─────────────────────

    private fun handleChunkFailure(
        chunk: DataChunk, throwable: Throwable, options: ImportOptions, chunkFailures: MutableList<ChunkFailure>,
    ): ChunkDecision {
        if (options.onError == OnError.LOG) {
            chunkFailures += ChunkFailure(chunk.table, chunk.chunkIndex, chunk.rows.size.toLong(),
                throwable.message ?: throwable::class.simpleName.orEmpty())
        }
        return when (options.onError) { OnError.ABORT -> ChunkDecision.ABORT; OnError.SKIP, OnError.LOG -> ChunkDecision.CONTINUE }
    }

    private fun tryReadNextChunk(
        reader: DataChunkReader, table: String, chunkIndex: Long, options: ImportOptions, chunkFailures: MutableList<ChunkFailure>,
    ): ReadNextChunkResult =
        try {
            reader.nextChunk()?.let(ReadNextChunkResult::Chunk) ?: ReadNextChunkResult.EndOfInput
        } catch (t: Throwable) {
            when (handleReaderFailure(table, chunkIndex, t, options, chunkFailures)) {
                ChunkDecision.ABORT -> throw t
                ChunkDecision.CONTINUE -> ReadNextChunkResult.Failed(t.message ?: t::class.simpleName.orEmpty())
            }
        }

    private fun handleReaderFailure(
        table: String, chunkIndex: Long, throwable: Throwable, options: ImportOptions, chunkFailures: MutableList<ChunkFailure>,
    ): ChunkDecision {
        if (options.onError == OnError.LOG) {
            chunkFailures += ChunkFailure(table, chunkIndex, 0, throwable.message ?: throwable::class.simpleName.orEmpty())
        }
        return when (options.onError) { OnError.ABORT -> ChunkDecision.ABORT; OnError.SKIP, OnError.LOG -> ChunkDecision.CONTINUE }
    }

    // ── lifecycle helpers ──────────────────────────

    private fun closeAndCollect(closeable: AutoCloseable?, primaryFailure: Throwable?, recordCleanupFailure: (Throwable) -> Unit) {
        if (closeable == null) return
        try { closeable.close() } catch (cleanup: Throwable) {
            if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else recordCleanupFailure(cleanup)
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun TargetColumn.asColumnDescriptor(): ColumnDescriptor = ColumnDescriptor(name, nullable, sqlTypeName)

    private fun FinishTableResult.PartialFailure.toFailedFinishInfo(): FailedFinishInfo {
        val closeCause = cause.suppressedExceptions.firstOrNull()
        return FailedFinishInfo(adjustments, cause.message.orEmpty(),
            cause::class.qualifiedName ?: cause.javaClass.name,
            cause.stackTrace.takeIf { it.isNotEmpty() }?.let { cause.stackTraceToString() },
            closeCause?.message, closeCause?.let { it::class.qualifiedName ?: it.javaClass.name },
            closeCause?.stackTrace?.takeIf { it.isNotEmpty() }?.let { closeCause.stackTraceToString() })
    }

    // ── internal types ────────────────────────────

    private data class BindingPlan(
        val sourceHeader: List<String>?, val boundTargetColumns: List<TargetColumn>,
        val sourceIndexes: List<Int>, val positional: Boolean,
    )

    private sealed interface ReadNextChunkResult {
        data class Chunk(val chunk: DataChunk) : ReadNextChunkResult
        data object EndOfInput : ReadNextChunkResult
        data class Failed(val reason: String) : ReadNextChunkResult
    }

    private enum class ChunkDecision { ABORT, CONTINUE }
}
