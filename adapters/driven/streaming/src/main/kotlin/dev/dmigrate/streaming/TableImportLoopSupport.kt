package dev.dmigrate.streaming

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnError
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ValueDeserializer

internal class ImportLoopState {
    var rowsInserted = 0L
    var rowsUpdated = 0L
    var rowsSkipped = 0L
    var rowsUnknown = 0L
    var rowsFailed = 0L
    val chunkFailures = mutableListOf<ChunkFailure>()
    /** LN-005 (R4): Anzahl der über [MAX_LOGGED_CHUNK_FAILURES] hinaus unterdrückten Detaileinträge. */
    var chunkFailuresSuppressed = 0L
    var sequenceAdjustments: List<SequenceAdjustment> = emptyList()
    var partialFinish: FinishTableResult.PartialFailure? = null
    var error: String? = null
    var targetColumns = emptyList<ColumnDescriptor>()
    var chunksCommittedTotal = 0L
    val totalRows: Long
        get() = rowsInserted + rowsUpdated + rowsSkipped + rowsUnknown + rowsFailed

    /**
     * LN-005 (R4): fügt einen Chunk-Fehler-Detaileintrag hinzu (nur `--on-error log`),
     * aber **gedeckelt** auf [MAX_LOGGED_CHUNK_FAILURES]. Darüber hinausgehende Fehler
     * werden nur gezählt ([chunkFailuresSuppressed]) statt die Liste unbounded wachsen zu
     * lassen (pathologisches „jeder Chunk schlägt fehl" auf einer sehr großen Tabelle → sonst
     * ~`totalRows/chunkSize` Einträge). Die **wahre** Fehlerzahl trägt ohnehin [rowsFailed];
     * die Liste ist ein begrenztes Detail-Sample.
     */
    fun recordChunkFailure(failure: ChunkFailure) {
        if (chunkFailures.size < MAX_LOGGED_CHUNK_FAILURES) {
            chunkFailures += failure
        } else {
            chunkFailuresSuppressed++
        }
    }

    companion object {
        /** LN-005 (R4): Obergrenze für protokollierte Chunk-Fehler-Detaileinträge (`--on-error log`). */
        const val MAX_LOGGED_CHUNK_FAILURES = 1000
    }
}

internal data class ChunkContext(
    val table: String,
    val ordinal: Int,
    val tableCount: Int,
    val options: ImportOptions,
    val format: DataExportFormat,
    val bindingPlan: BindingPlan,
    val deserializer: ValueDeserializer,
)

internal fun importChunks(
    reader: DataChunkReader,
    session: TableImportSession,
    context: ChunkContext,
    state: ImportLoopState,
    reporter: ProgressReporter,
    onChunkCommitted: (ImportChunkCommit) -> Unit,
    firstChunk: DataChunk?,
    cancellationToken: CancellationToken = CancellationToken.none(),
) {
    var nextChunk: DataChunk? = firstChunk
    while (nextChunk != null) {
        // LF-010 / LF-013 / LN-009 / LN-012: cancel between chunks must not start the next chunk's
        // normalize/write. Cancel checkpoints sit OUTSIDE the per-call try
        // blocks so OperationCancelledException never travels through the
        // chunk-failure catches (would otherwise be mapped to
        // a fachlicher chunk error).
        cancellationToken.throwIfCancellationRequested()

        val normalizedChunk = try {
            normalizeChunk(
                nextChunk,
                context.table,
                context.bindingPlan,
                context.deserializer,
                context.format == DataExportFormat.CSV,
            )
        } catch (throwable: Throwable) {
            val decision = handleNormalizationFailure(nextChunk, throwable, context, state, reporter)
            if (decision == LoopAction.ABORT) throw throwable
            nextChunk = advanceOrBreak(reader, context, state, nextChunk.chunkIndex + 1) ?: break
            continue
        }

        cancellationToken.throwIfCancellationRequested()
        val writeResult = try {
            session.write(normalizedChunk)
        } catch (throwable: Throwable) {
            val action = handleWriteFailure(session, normalizedChunk, throwable, context, state, reporter)
            if (action == LoopAction.ABORT) throw throwable
            if (action == LoopAction.BREAK) break
            nextChunk = advanceOrBreak(reader, context, state, normalizedChunk.chunkIndex + 1) ?: break
            continue
        }

        cancellationToken.throwIfCancellationRequested()
        val commitFailed = commitAndAccount(session, writeResult, normalizedChunk, context, state, reporter)
        if (commitFailed) break

        // LF-010 / LF-013 / LN-012: callbacks that persist progress are side effects. Cancel
        // before the callback prevents a fake-progress manifest entry for an
        // otherwise-finished chunk.
        cancellationToken.throwIfCancellationRequested()
        runCatching {
            onChunkCommitted(
                ImportChunkCommit(
                    table = context.table,
                    chunkIndex = normalizedChunk.chunkIndex,
                    chunksCommitted = state.chunksCommittedTotal,
                    rowsInsertedTotal = state.rowsInserted,
                    rowsUpdatedTotal = state.rowsUpdated,
                    rowsSkippedTotal = state.rowsSkipped,
                    rowsUnknownTotal = state.rowsUnknown,
                    rowsFailedTotal = state.rowsFailed,
                )
            )
        }

        nextChunk = advanceOrBreak(reader, context, state, normalizedChunk.chunkIndex + 1) ?: break
    }
}

/**
 * Commits the chunk and updates loop state. Returns `true` if a
 * non-fatal commit failure asked the loop to break; throws on ABORT.
 * Extracted from [importChunks] so the cancel checkpoint can sit
 * cleanly outside the chunk-failure catch.
 */
private fun commitAndAccount(
    session: TableImportSession,
    writeResult: WriteResult,
    normalizedChunk: DataChunk,
    context: ChunkContext,
    state: ImportLoopState,
    reporter: ProgressReporter,
): Boolean {
    return try {
        session.commitChunk()
        applyWriteResult(writeResult, state)
        reportChunkProcessed(reporter, context, normalizedChunk, state)
        state.chunksCommittedTotal += 1
        false
    } catch (throwable: Throwable) {
        state.rowsFailed += normalizedChunk.rows.size.toLong()
        when (handleChunkFailure(normalizedChunk, throwable, context.options, state)) {
            ChunkDecision.ABORT -> throw throwable
            ChunkDecision.CONTINUE -> {
                state.error = throwable.message ?: throwable::class.simpleName
                true
            }
        }
    }
}

private fun applyWriteResult(writeResult: WriteResult, state: ImportLoopState) {
    state.rowsInserted += writeResult.rowsInserted
    state.rowsUpdated += writeResult.rowsUpdated
    state.rowsSkipped += writeResult.rowsSkipped
    state.rowsUnknown += writeResult.rowsUnknown
}

private fun handleNormalizationFailure(
    chunk: DataChunk,
    throwable: Throwable,
    context: ChunkContext,
    state: ImportLoopState,
    reporter: ProgressReporter,
): LoopAction =
    when (handleChunkFailure(chunk, throwable, context.options, state)) {
        ChunkDecision.ABORT -> LoopAction.ABORT
        ChunkDecision.CONTINUE -> {
            state.rowsFailed += chunk.rows.size.toLong()
            reportChunkProcessed(reporter, context, chunk, state)
            LoopAction.CONTINUE
        }
    }

private fun handleWriteFailure(
    session: TableImportSession,
    chunk: DataChunk,
    throwable: Throwable,
    context: ChunkContext,
    state: ImportLoopState,
    reporter: ProgressReporter,
): LoopAction {
    val recovered = runCatching { session.rollbackChunk() }.isSuccess
    state.rowsFailed += chunk.rows.size.toLong()
    return when (handleChunkFailure(chunk, throwable, context.options, state)) {
        ChunkDecision.ABORT -> LoopAction.ABORT
        ChunkDecision.CONTINUE -> {
            reportChunkProcessed(reporter, context, chunk, state)
            if (!recovered) {
                state.error = throwable.message ?: throwable::class.simpleName
                LoopAction.BREAK
            } else {
                LoopAction.CONTINUE
            }
        }
    }
}

private fun reportChunkProcessed(
    reporter: ProgressReporter,
    context: ChunkContext,
    chunk: DataChunk,
    state: ImportLoopState,
) {
    reporter.report(
        ProgressEvent.ImportChunkProcessed(
            table = context.table,
            tableOrdinal = context.ordinal,
            tableCount = context.tableCount,
            chunkIndex = chunk.chunkIndex.toInt() + 1,
            rowsInChunk = chunk.rows.size.toLong(),
            rowsProcessed = state.totalRows,
            rowsInserted = state.rowsInserted,
            rowsUpdated = state.rowsUpdated,
            rowsSkipped = state.rowsSkipped,
            rowsUnknown = state.rowsUnknown,
            rowsFailed = state.rowsFailed,
        )
    )
}

private fun advanceOrBreak(
    reader: DataChunkReader,
    context: ChunkContext,
    state: ImportLoopState,
    nextIndex: Long,
): DataChunk? =
    when (
        val readResult = tryReadNextChunk(
            reader = reader,
            table = context.table,
            chunkIndex = nextIndex,
            options = context.options,
            state = state,
        )
    ) {
        is ReadNextChunkResult.Chunk -> readResult.chunk
        is ReadNextChunkResult.EndOfInput -> null
        is ReadNextChunkResult.Failed -> {
            state.error = readResult.reason
            null
        }
    }

private fun handleChunkFailure(
    chunk: DataChunk,
    throwable: Throwable,
    options: ImportOptions,
    state: ImportLoopState,
): ChunkDecision {
    if (options.onError == OnError.LOG) {
        state.recordChunkFailure(
            ChunkFailure(
                chunk.table,
                chunk.chunkIndex,
                chunk.rows.size.toLong(),
                throwable.message ?: throwable::class.simpleName.orEmpty(),
            )
        )
    }
    return when (options.onError) {
        OnError.ABORT -> ChunkDecision.ABORT
        OnError.SKIP, OnError.LOG -> ChunkDecision.CONTINUE
    }
}

private fun tryReadNextChunk(
    reader: DataChunkReader,
    table: String,
    chunkIndex: Long,
    options: ImportOptions,
    state: ImportLoopState,
): ReadNextChunkResult =
    try {
        reader.nextChunk()?.let(ReadNextChunkResult::Chunk) ?: ReadNextChunkResult.EndOfInput
    } catch (throwable: Throwable) {
        when (handleReaderFailure(table, chunkIndex, throwable, options, state)) {
            ChunkDecision.ABORT -> throw throwable
            ChunkDecision.CONTINUE -> ReadNextChunkResult.Failed(
                throwable.message ?: throwable::class.simpleName.orEmpty()
            )
        }
    }

private fun handleReaderFailure(
    table: String,
    chunkIndex: Long,
    throwable: Throwable,
    options: ImportOptions,
    state: ImportLoopState,
): ChunkDecision {
    if (options.onError == OnError.LOG) {
        state.recordChunkFailure(
            ChunkFailure(
                table,
                chunkIndex,
                0,
                throwable.message ?: throwable::class.simpleName.orEmpty(),
            )
        )
    }
    return when (options.onError) {
        OnError.ABORT -> ChunkDecision.ABORT
        OnError.SKIP, OnError.LOG -> ChunkDecision.CONTINUE
    }
}

private enum class LoopAction { ABORT, BREAK, CONTINUE }

private sealed interface ReadNextChunkResult {
    data class Chunk(val chunk: DataChunk) : ReadNextChunkResult
    data object EndOfInput : ReadNextChunkResult
    data class Failed(val reason: String) : ReadNextChunkResult
}

private enum class ChunkDecision { ABORT, CONTINUE }
