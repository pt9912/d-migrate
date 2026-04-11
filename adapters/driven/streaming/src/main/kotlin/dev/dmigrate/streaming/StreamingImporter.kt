package dev.dmigrate.streaming

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnError
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.DataChunkReaderFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.JdbcTypeHint
import dev.dmigrate.format.data.ValueDeserializer
import java.io.InputStream
import java.nio.file.Files
import kotlin.io.path.name

/**
 * Pull-basierter Streaming-Importer. Liest Chunks aus einem Reader und
 * schreibt sie über den dialektspezifischen Writer chunkweise in die DB.
 */
class StreamingImporter(
    private val readerFactory: DataChunkReaderFactory,
    private val writerLookup: (DatabaseDialect) -> DataWriter,
) {

    fun import(
        pool: ConnectionPool,
        input: ImportInput,
        format: DataExportFormat,
        options: ImportOptions = ImportOptions(),
        config: PipelineConfig = PipelineConfig(),
    ): ImportResult {
        val writer = writerLookup(pool.dialect)
        val tableInputs = resolveInputs(input, format)
        require(tableInputs.isNotEmpty()) {
            "No tables to import from $input"
        }

        val startedAt = System.nanoTime()
        val summaries = mutableListOf<TableImportSummary>()

        for (tableInput in tableInputs) {
            summaries += importSingleTable(
                pool = pool,
                writer = writer,
                tableInput = tableInput,
                format = format,
                options = options,
                config = config,
            )
        }

        return buildResult(summaries, startedAt)
    }

    private fun importSingleTable(
        pool: ConnectionPool,
        writer: DataWriter,
        tableInput: ResolvedTableInput,
        format: DataExportFormat,
        options: ImportOptions,
        config: PipelineConfig,
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

        try {
            reader = readerFactory.create(
                format = format,
                input = tableInput.openInput(),
                table = tableInput.table,
                chunkSize = config.chunkSize,
                options = options,
            )
            session = writer.openTable(pool, tableInput.table, options)
            targetColumns = session.targetColumns.map { it.asColumnDescriptor() }

            val firstChunk = reader.nextChunk()
            val bindingPlan = buildBindingPlan(
                table = tableInput.table,
                headerColumns = reader.headerColumns(),
                firstChunk = firstChunk,
                targetColumns = session.targetColumns,
            )
            val deserializer = buildDeserializer(session.targetColumns, options)

            var nextChunk: DataChunk? = firstChunk
            var nextChunkIndex = firstChunk?.chunkIndex ?: 0L
            while (nextChunk != null) {
                val normalizedChunk = try {
                    normalizeChunk(
                        chunk = nextChunk,
                        table = tableInput.table,
                        bindingPlan = bindingPlan,
                        deserializer = deserializer,
                        isCsvSource = format == DataExportFormat.CSV,
                    )
                } catch (t: Throwable) {
                    when (handleChunkFailure(nextChunk, t, options, chunkFailures)) {
                        ChunkDecision.ABORT -> throw t
                        ChunkDecision.CONTINUE -> {
                            rowsFailed += nextChunk.rows.size.toLong()
                            when (
                                val readResult = tryReadNextChunk(
                                    reader = reader,
                                    table = tableInput.table,
                                    chunkIndex = nextChunk.chunkIndex + 1,
                                    options = options,
                                    chunkFailures = chunkFailures,
                                )
                            ) {
                                is ReadNextChunkResult.Chunk -> {
                                    nextChunk = readResult.chunk
                                    nextChunkIndex = nextChunk.chunkIndex
                                }
                                is ReadNextChunkResult.EndOfInput -> break
                                is ReadNextChunkResult.Failed -> {
                                    error = readResult.reason
                                    break
                                }
                            }
                            continue
                        }
                    }
                }

                val writeResult = try {
                    session.write(normalizedChunk)
                } catch (t: Throwable) {
                    val recovered = attemptRollback(session)
                    rowsFailed += normalizedChunk.rows.size.toLong()
                    when (handleChunkFailure(normalizedChunk, t, options, chunkFailures)) {
                        ChunkDecision.ABORT -> throw t
                        ChunkDecision.CONTINUE -> {
                            if (!recovered) {
                                error = t.message ?: t::class.simpleName
                                break
                            }
                            when (
                                val readResult = tryReadNextChunk(
                                    reader = reader,
                                    table = tableInput.table,
                                    chunkIndex = normalizedChunk.chunkIndex + 1,
                                    options = options,
                                    chunkFailures = chunkFailures,
                                )
                            ) {
                                is ReadNextChunkResult.Chunk -> {
                                    nextChunk = readResult.chunk
                                    nextChunkIndex = nextChunk.chunkIndex
                                }
                                is ReadNextChunkResult.EndOfInput -> break
                                is ReadNextChunkResult.Failed -> {
                                    error = readResult.reason
                                    break
                                }
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
                } catch (t: Throwable) {
                    rowsFailed += normalizedChunk.rows.size.toLong()
                    when (handleChunkFailure(normalizedChunk, t, options, chunkFailures)) {
                        ChunkDecision.ABORT -> throw t
                        ChunkDecision.CONTINUE -> {
                            error = t.message ?: t::class.simpleName
                            break
                        }
                    }
                }

                nextChunkIndex = normalizedChunk.chunkIndex + 1
                when (
                    val readResult = tryReadNextChunk(
                        reader = reader,
                        table = tableInput.table,
                        chunkIndex = nextChunkIndex,
                        options = options,
                        chunkFailures = chunkFailures,
                    )
                ) {
                    is ReadNextChunkResult.Chunk -> {
                        nextChunk = readResult.chunk
                        nextChunkIndex = nextChunk.chunkIndex
                    }
                    is ReadNextChunkResult.EndOfInput -> break
                    is ReadNextChunkResult.Failed -> {
                        error = readResult.reason
                        break
                    }
                }
            }

            if (error == null) {
                when (val finish = session.finishTable()) {
                    is FinishTableResult.Success -> {
                        sequenceAdjustments = finish.adjustments
                    }
                    is FinishTableResult.PartialFailure -> {
                        sequenceAdjustments = finish.adjustments
                        partialFinish = finish
                    }
                }
            }
        } catch (t: Throwable) {
            primaryFailure = t
            throw t
        } finally {
            var cleanupFailure: Throwable? = null
            closeAndCollect(reader, primaryFailure) { cleanup ->
                cleanupFailure = cleanupFailure?.apply { addSuppressed(cleanup) } ?: cleanup
            }
            closeAndCollect(session, primaryFailure) { cleanup ->
                cleanupFailure = cleanupFailure?.apply { addSuppressed(cleanup) } ?: cleanup
            }
            if (primaryFailure == null && cleanupFailure != null) {
                throw cleanupFailure!!
            }
        }

        return TableImportSummary(
            table = tableInput.table,
            rowsInserted = rowsInserted,
            rowsUpdated = rowsUpdated,
            rowsSkipped = rowsSkipped,
            rowsUnknown = rowsUnknown,
            rowsFailed = rowsFailed,
            chunkFailures = chunkFailures.toList(),
            sequenceAdjustments = sequenceAdjustments,
            targetColumns = targetColumns,
            triggerMode = options.triggerMode,
            failedFinish = partialFinish?.toFailedFinishInfo(),
            error = error,
            durationMs = elapsedMs(tableStartedAt),
        )
    }

    private fun handleChunkFailure(
        chunk: DataChunk,
        throwable: Throwable,
        options: ImportOptions,
        chunkFailures: MutableList<ChunkFailure>,
    ): ChunkDecision {
        if (options.onError == OnError.LOG) {
            chunkFailures += ChunkFailure(
                table = chunk.table,
                chunkIndex = chunk.chunkIndex,
                rowsLost = chunk.rows.size.toLong(),
                reason = throwable.message ?: throwable::class.simpleName.orEmpty(),
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
        chunkFailures: MutableList<ChunkFailure>,
    ): ReadNextChunkResult =
        try {
            reader.nextChunk()?.let(ReadNextChunkResult::Chunk) ?: ReadNextChunkResult.EndOfInput
        } catch (t: Throwable) {
            when (handleReaderFailure(table, chunkIndex, t, options, chunkFailures)) {
                ChunkDecision.ABORT -> throw t
                ChunkDecision.CONTINUE -> ReadNextChunkResult.Failed(
                    t.message ?: t::class.simpleName.orEmpty(),
                )
            }
        }

    private fun handleReaderFailure(
        table: String,
        chunkIndex: Long,
        throwable: Throwable,
        options: ImportOptions,
        chunkFailures: MutableList<ChunkFailure>,
    ): ChunkDecision {
        if (options.onError == OnError.LOG) {
            chunkFailures += ChunkFailure(
                table = table,
                chunkIndex = chunkIndex,
                rowsLost = 0,
                reason = throwable.message ?: throwable::class.simpleName.orEmpty(),
            )
        }
        return when (options.onError) {
            OnError.ABORT -> ChunkDecision.ABORT
            OnError.SKIP, OnError.LOG -> ChunkDecision.CONTINUE
        }
    }

    private fun buildBindingPlan(
        table: String,
        headerColumns: List<String>?,
        firstChunk: DataChunk?,
        targetColumns: List<TargetColumn>,
    ): BindingPlan {
        if (headerColumns == null) {
            return BindingPlan(
                sourceHeader = null,
                boundTargetColumns = targetColumns,
                sourceIndexes = targetColumns.indices.toList(),
                positional = true,
            )
        }

        val duplicates = headerColumns.groupBy { it }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ImportSchemaMismatchException(
                "Header for table '$table' contains duplicate columns: ${duplicates.joinToString()}"
            )
        }

        if (firstChunk != null && firstChunk.columns.isNotEmpty()) {
            val chunkColumns = firstChunk.columns.map { it.name }
            if (chunkColumns != headerColumns) {
                throw ImportSchemaMismatchException(
                    "Header/first chunk mismatch for table '$table': expected $headerColumns, got $chunkColumns"
                )
            }
        }

        val targetByName = targetColumns.associateBy { it.name }
        val unknown = headerColumns.filterNot(targetByName::containsKey)
        if (unknown.isNotEmpty()) {
            throw ImportSchemaMismatchException(
                "Target table '$table' has no columns ${unknown.joinToString()}"
            )
        }

        val sourceIndexByName = headerColumns.withIndex().associate { it.value to it.index }
        val boundColumns = targetColumns.filter { sourceIndexByName.containsKey(it.name) }
        val sourceIndexes = boundColumns.map { sourceIndexByName.getValue(it.name) }
        return BindingPlan(
            sourceHeader = headerColumns,
            boundTargetColumns = boundColumns,
            sourceIndexes = sourceIndexes,
            positional = false,
        )
    }

    private fun normalizeChunk(
        chunk: DataChunk,
        table: String,
        bindingPlan: BindingPlan,
        deserializer: ValueDeserializer,
        isCsvSource: Boolean,
    ): DataChunk {
        if (!bindingPlan.positional && bindingPlan.sourceHeader != null) {
            val currentHeader = chunk.columns.map { it.name }
            if (currentHeader.isNotEmpty() && currentHeader != bindingPlan.sourceHeader) {
                throw ImportSchemaMismatchException(
                    "All chunks for table '$table' must use the same header order; " +
                        "expected ${bindingPlan.sourceHeader}, got $currentHeader"
                )
            }
        }

        val normalizedRows = chunk.rows.mapIndexed { rowIndex, row ->
            if (bindingPlan.positional) {
                if (row.size != bindingPlan.boundTargetColumns.size) {
                    throw ImportSchemaMismatchException(
                        "Chunk row $rowIndex for table '$table' has ${row.size} values, " +
                            "expected ${bindingPlan.boundTargetColumns.size}"
                    )
                }
            } else if (bindingPlan.sourceHeader != null && row.size != bindingPlan.sourceHeader.size) {
                throw ImportSchemaMismatchException(
                    "Chunk row $rowIndex for table '$table' has ${row.size} values, " +
                        "expected ${bindingPlan.sourceHeader.size}"
                )
            }

            Array(bindingPlan.boundTargetColumns.size) { idx ->
                val targetColumn = bindingPlan.boundTargetColumns[idx]
                val sourceValue = row[bindingPlan.sourceIndexes[idx]]
                deserializer.deserialize(
                    table = table,
                    columnName = targetColumn.name,
                    value = sourceValue,
                    isCsvSource = isCsvSource,
                )
            }
        }

        return DataChunk(
            table = chunk.table,
            columns = bindingPlan.boundTargetColumns.map { it.asColumnDescriptor() },
            rows = normalizedRows,
            chunkIndex = chunk.chunkIndex,
        )
    }

    private fun buildDeserializer(
        targetColumns: List<TargetColumn>,
        options: ImportOptions,
    ): ValueDeserializer {
        val hints = targetColumns.associate { column ->
            column.name to JdbcTypeHint(
                jdbcType = column.jdbcType,
                sqlTypeName = column.sqlTypeName,
            )
        }
        return ValueDeserializer(
            typeHintOf = { hints[it] },
            csvNullString = options.csvNullString,
        )
    }

    private fun resolveInputs(
        input: ImportInput,
        format: DataExportFormat,
    ): List<ResolvedTableInput> =
        when (input) {
            is ImportInput.Stdin ->
                listOf(
                    ResolvedTableInput(
                        table = input.table,
                        openInput = { input.input },
                    )
                )

            is ImportInput.SingleFile ->
                listOf(
                    ResolvedTableInput(
                        table = input.table,
                        openInput = { Files.newInputStream(input.path) },
                    )
                )

            is ImportInput.Directory -> resolveDirectoryInputs(input, format)
        }

    private fun resolveDirectoryInputs(
        input: ImportInput.Directory,
        format: DataExportFormat,
    ): List<ResolvedTableInput> {
        require(Files.isDirectory(input.path)) {
            "ImportInput.Directory path '${input.path}' is not a directory"
        }
        val tableFilter = input.tableFilter
        val tableOrder = input.tableOrder

        val suffix = ".${format.cliName}"
        val candidates = Files.list(input.path).use { entries ->
            entries
                .filter(Files::isRegularFile)
                .filter { it.fileName.name.endsWith(suffix) }
                .toList()
                .associateBy(
                    keySelector = { it.fileName.name.removeSuffix(suffix) },
                    valueTransform = { it },
                )
        }.toMutableMap()

        if (tableFilter != null) {
            val missing = tableFilter.filterNot(candidates::containsKey)
            require(missing.isEmpty()) {
                "ImportInput.Directory.tableFilter references tables without matching files: ${missing.joinToString()}"
            }
            candidates.keys.retainAll(tableFilter.toSet())
        }

        val orderedTables = if (tableOrder != null) {
            val duplicates = tableOrder.groupBy { it }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) {
                "ImportInput.Directory.tableOrder contains duplicate tables: ${duplicates.joinToString()}"
            }
            val missing = tableOrder.filterNot(candidates::containsKey)
            require(missing.isEmpty()) {
                "ImportInput.Directory.tableOrder references tables without matching files: ${missing.joinToString()}"
            }
            val extras = candidates.keys - tableOrder.toSet()
            require(extras.isEmpty()) {
                "ImportInput.Directory.tableOrder must cover all candidate tables, missing order for: ${extras.joinToString()}"
            }
            tableOrder
        } else {
            candidates.keys.sorted()
        }

        return orderedTables.map { table ->
            ResolvedTableInput(
                table = table,
                openInput = { Files.newInputStream(candidates.getValue(table)) },
            )
        }
    }

    private fun attemptRollback(session: TableImportSession): Boolean =
        runCatching {
            session.rollbackChunk()
        }.isSuccess

    private fun buildResult(
        summaries: List<TableImportSummary>,
        startedAt: Long,
    ): ImportResult =
        ImportResult(
            tables = summaries,
            totalRowsInserted = summaries.sumOf { it.rowsInserted },
            totalRowsUpdated = summaries.sumOf { it.rowsUpdated },
            totalRowsSkipped = summaries.sumOf { it.rowsSkipped },
            totalRowsUnknown = summaries.sumOf { it.rowsUnknown },
            totalRowsFailed = summaries.sumOf { it.rowsFailed },
            durationMs = elapsedMs(startedAt),
        )

    private fun TargetColumn.asColumnDescriptor(): ColumnDescriptor =
        ColumnDescriptor(
            name = name,
            nullable = nullable,
            sqlTypeName = sqlTypeName,
        )

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

    private fun closeAndCollect(
        closeable: AutoCloseable?,
        primaryFailure: Throwable?,
        recordCleanupFailure: (Throwable) -> Unit,
    ) {
        if (closeable == null) return
        try {
            closeable.close()
        } catch (cleanup: Throwable) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanup)
            } else {
                recordCleanupFailure(cleanup)
            }
        }
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private data class ResolvedTableInput(
        val table: String,
        val openInput: () -> InputStream,
    )

    private data class BindingPlan(
        val sourceHeader: List<String>?,
        val boundTargetColumns: List<TargetColumn>,
        val sourceIndexes: List<Int>,
        val positional: Boolean,
    )

    private sealed interface ReadNextChunkResult {
        data class Chunk(val chunk: DataChunk) : ReadNextChunkResult
        data object EndOfInput : ReadNextChunkResult
        data class Failed(val reason: String) : ReadNextChunkResult
    }

    private enum class ChunkDecision { ABORT, CONTINUE }
}
