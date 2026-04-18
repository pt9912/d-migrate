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
internal class TableImporter(
    private val readerFactory: DataChunkReaderFactory,
    private val onTableOpened: (table: String, targetColumns: List<TargetColumn>) -> Unit,
) {

    fun import(
        pool: ConnectionPool,
        writer: DataWriter,
        tableInput: ResolvedTableInput,
        format: DataExportFormat,
        options: ImportOptions,
        readOptions: FormatReadOptions = FormatReadOptions(),
        config: PipelineConfig,
        reporter: ProgressReporter,
        ordinal: Int,
        tableCount: Int,
        resumeState: ImportTableResumeState?,
        onChunkCommitted: (ImportChunkCommit) -> Unit,
    ): TableImportSummary {
        val tableStartedAt = System.nanoTime()
        var reader: DataChunkReader? = null
        var session: TableImportSession? = null
        var primaryFailure: Throwable? = null
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

        val committedChunksOffset: Long = resumeState?.committedChunks ?: 0L
        val effectiveOptions = if (committedChunksOffset > 0L) {
            options.copy(truncate = false)
        } else {
            options
        }
        var chunksCommittedTotal: Long = committedChunksOffset

        try {
            reader = readerFactory.create(
                format = format,
                input = tableInput.openInput(),
                table = tableInput.table,
                chunkSize = config.chunkSize,
                options = readOptions,
            )
            session = writer.openTable(pool, tableInput.table, effectiveOptions)
            onTableOpened(tableInput.table, session.targetColumns)
            reporter.report(ProgressEvent.ImportTableStarted(tableInput.table, ordinal, tableCount))
            targetColumns = session.targetColumns.map { it.asColumnDescriptor() }

            if (committedChunksOffset > 0L) {
                var skipped = 0L
                while (skipped < committedChunksOffset) {
                    reader.nextChunk() ?: break
                    skipped += 1
                }
            }

            val firstChunk = reader.nextChunk()
            val bindingPlan = buildBindingPlan(
                table = tableInput.table,
                headerColumns = reader.headerColumns(),
                firstChunk = firstChunk,
                targetColumns = session.targetColumns,
            )
            val deserializer = buildDeserializer(session.targetColumns, readOptions)

            var nextChunk: DataChunk? = firstChunk
            var nextChunkIndex = firstChunk?.chunkIndex ?: 0L
            while (nextChunk != null) {
                val normalizedChunk = try {
                    normalizeChunk(nextChunk, tableInput.table, bindingPlan, deserializer,
                        format == DataExportFormat.CSV)
                } catch (t: Throwable) {
                    when (handleChunkFailure(nextChunk, t, options, chunkFailures)) {
                        ChunkDecision.ABORT -> throw t
                        ChunkDecision.CONTINUE -> {
                            rowsFailed += nextChunk.rows.size.toLong()
                            reporter.report(ProgressEvent.ImportChunkProcessed(
                                table = tableInput.table, tableOrdinal = ordinal, tableCount = tableCount,
                                chunkIndex = nextChunk.chunkIndex.toInt() + 1,
                                rowsInChunk = nextChunk.rows.size.toLong(),
                                rowsProcessed = rowsInserted + rowsUpdated + rowsSkipped + rowsUnknown + rowsFailed,
                                rowsInserted = rowsInserted, rowsUpdated = rowsUpdated,
                                rowsSkipped = rowsSkipped, rowsUnknown = rowsUnknown, rowsFailed = rowsFailed,
                            ))
                            when (val readResult = tryReadNextChunk(reader, tableInput.table,
                                nextChunk.chunkIndex + 1, options, chunkFailures)) {
                                is ReadNextChunkResult.Chunk -> { nextChunk = readResult.chunk; nextChunkIndex = nextChunk!!.chunkIndex }
                                is ReadNextChunkResult.EndOfInput -> break
                                is ReadNextChunkResult.Failed -> { error = readResult.reason; break }
                            }
                            continue
                        }
                    }
                }

                val writeResult = try {
                    session.write(normalizedChunk)
                } catch (t: Throwable) {
                    val recovered = runCatching { session.rollbackChunk() }.isSuccess
                    rowsFailed += normalizedChunk.rows.size.toLong()
                    when (handleChunkFailure(normalizedChunk, t, options, chunkFailures)) {
                        ChunkDecision.ABORT -> throw t
                        ChunkDecision.CONTINUE -> {
                            reporter.report(ProgressEvent.ImportChunkProcessed(
                                table = tableInput.table, tableOrdinal = ordinal, tableCount = tableCount,
                                chunkIndex = normalizedChunk.chunkIndex.toInt() + 1,
                                rowsInChunk = normalizedChunk.rows.size.toLong(),
                                rowsProcessed = rowsInserted + rowsUpdated + rowsSkipped + rowsUnknown + rowsFailed,
                                rowsInserted = rowsInserted, rowsUpdated = rowsUpdated,
                                rowsSkipped = rowsSkipped, rowsUnknown = rowsUnknown, rowsFailed = rowsFailed,
                            ))
                            if (!recovered) { error = t.message ?: t::class.simpleName; break }
                            when (val readResult = tryReadNextChunk(reader, tableInput.table,
                                normalizedChunk.chunkIndex + 1, options, chunkFailures)) {
                                is ReadNextChunkResult.Chunk -> { nextChunk = readResult.chunk; nextChunkIndex = nextChunk!!.chunkIndex }
                                is ReadNextChunkResult.EndOfInput -> break
                                is ReadNextChunkResult.Failed -> { error = readResult.reason; break }
                            }
                            continue
                        }
                    }
                }

                try {
                    session.commitChunk()
                    rowsInserted += writeResult.rowsInserted
                    rowsUpdated += writeResult.rowsUpdated
                    rowsSkipped += writeResult.rowsSkipped
                    rowsUnknown += writeResult.rowsUnknown
                    reporter.report(ProgressEvent.ImportChunkProcessed(
                        table = tableInput.table, tableOrdinal = ordinal, tableCount = tableCount,
                        chunkIndex = normalizedChunk.chunkIndex.toInt() + 1,
                        rowsInChunk = normalizedChunk.rows.size.toLong(),
                        rowsProcessed = rowsInserted + rowsUpdated + rowsSkipped + rowsUnknown + rowsFailed,
                        rowsInserted = rowsInserted, rowsUpdated = rowsUpdated,
                        rowsSkipped = rowsSkipped, rowsUnknown = rowsUnknown, rowsFailed = rowsFailed,
                    ))
                    chunksCommittedTotal += 1
                    runCatching {
                        onChunkCommitted(ImportChunkCommit(
                            table = tableInput.table, chunkIndex = normalizedChunk.chunkIndex,
                            chunksCommitted = chunksCommittedTotal,
                            rowsInsertedTotal = rowsInserted, rowsUpdatedTotal = rowsUpdated,
                            rowsSkippedTotal = rowsSkipped, rowsUnknownTotal = rowsUnknown,
                            rowsFailedTotal = rowsFailed,
                        ))
                    }
                } catch (t: Throwable) {
                    rowsFailed += normalizedChunk.rows.size.toLong()
                    when (handleChunkFailure(normalizedChunk, t, options, chunkFailures)) {
                        ChunkDecision.ABORT -> throw t
                        ChunkDecision.CONTINUE -> { error = t.message ?: t::class.simpleName; break }
                    }
                }

                nextChunkIndex = normalizedChunk.chunkIndex + 1
                when (val readResult = tryReadNextChunk(reader, tableInput.table,
                    nextChunkIndex, options, chunkFailures)) {
                    is ReadNextChunkResult.Chunk -> { nextChunk = readResult.chunk; nextChunkIndex = nextChunk!!.chunkIndex }
                    is ReadNextChunkResult.EndOfInput -> break
                    is ReadNextChunkResult.Failed -> { error = readResult.reason; break }
                }
            }

            if (error == null) {
                when (val finish = session.finishTable()) {
                    is FinishTableResult.Success -> sequenceAdjustments = finish.adjustments
                    is FinishTableResult.PartialFailure -> { sequenceAdjustments = finish.adjustments; partialFinish = finish }
                }
            }
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            var cleanupFailure: Throwable? = null
            closeAndCollect(reader, primaryFailure) { cleanupFailure = cleanupFailure?.apply { addSuppressed(it) } ?: it }
            closeAndCollect(session, primaryFailure) { cleanupFailure = cleanupFailure?.apply { addSuppressed(it) } ?: it }
            if (primaryFailure == null && cleanupFailure != null) throw cleanupFailure!!
        }

        val tableDurationMs = elapsedMs(tableStartedAt)
        val tableStatus = if (error == null && partialFinish == null) TableProgressStatus.COMPLETED else TableProgressStatus.FAILED
        reporter.report(ProgressEvent.ImportTableFinished(
            table = tableInput.table, tableOrdinal = ordinal, tableCount = tableCount,
            rowsInserted = rowsInserted, rowsUpdated = rowsUpdated,
            rowsSkipped = rowsSkipped, rowsUnknown = rowsUnknown, rowsFailed = rowsFailed,
            durationMs = tableDurationMs, status = tableStatus,
        ))

        return TableImportSummary(
            table = tableInput.table,
            rowsInserted = rowsInserted, rowsUpdated = rowsUpdated,
            rowsSkipped = rowsSkipped, rowsUnknown = rowsUnknown, rowsFailed = rowsFailed,
            chunkFailures = chunkFailures.toList(),
            sequenceAdjustments = sequenceAdjustments,
            targetColumns = targetColumns,
            triggerMode = options.triggerMode,
            failedFinish = partialFinish?.toFailedFinishInfo(),
            error = error, durationMs = tableDurationMs,
        )
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
