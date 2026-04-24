package dev.dmigrate.streaming

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.DataChunkReaderFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import java.io.InputStream
import java.sql.Connection

internal object ImporterNoopConnectionPool : ConnectionPool {
    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE
    override fun borrow(): Connection = error("Fake importer tests do not borrow JDBC connections")
    override fun activeConnections(): Int = 0
    override fun close() = Unit
}

internal class FakeReaderFactory(
    private val readersByTable: Map<String, FakeReader>,
) : DataChunkReaderFactory {
    val createdTables = mutableListOf<String>()
    val seenInputs = mutableListOf<InputStream>()

    override fun create(
        format: DataExportFormat,
        input: InputStream,
        table: String,
        chunkSize: Int,
        options: FormatReadOptions,
    ): DataChunkReader {
        createdTables += table
        seenInputs += input
        return readersByTable[table] ?: error("No fake reader configured for table '$table'")
    }
}

internal class FakeReader(
    private val header: List<String>?,
    private val chunks: List<DataChunk>,
    private val nextChunkFailures: Map<Int, Throwable> = emptyMap(),
    private val closeThrowable: Throwable? = null,
) : DataChunkReader {
    private var index = 0

    override fun nextChunk(): DataChunk? {
        nextChunkFailures[index]?.let { throw it }
        return if (index >= chunks.size) null else chunks[index++]
    }

    override fun headerColumns(): List<String>? = header

    override fun close() {
        closeThrowable?.let { throw it }
    }
}

internal class FakeWriter(
    private val sessions: Map<String, FakeTableImportSession>,
) : DataWriter {
    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    override fun schemaSync() = error("not used in streaming importer tests")

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession = sessions[table] ?: error("No fake session for table '$table'")
}

internal open class FakeTableImportSession(
    override val targetColumns: List<TargetColumn>,
    private val writeResults: Map<Long, WriteResult> = emptyMap(),
    private val writeBehaviors: Map<Long, WriteBehavior> = emptyMap(),
    private val finishResult: FinishTableResult = FinishTableResult.Success(emptyList()),
    private val closeHook: (() -> Unit)? = null,
) : TableImportSession {

    sealed interface WriteBehavior {
        data class WriteFails(val throwable: Throwable) : WriteBehavior
    }

    val writtenChunks = mutableListOf<DataChunk>()
    var closeCount = 0
        private set

    private var nextChunkIndex = 0L
    private var state: State = State.OPEN

    private enum class State { OPEN, WRITTEN, FAILED, FINISHED, CLOSED }

    override fun write(chunk: DataChunk): WriteResult {
        check(state == State.OPEN) { "write requires OPEN, current state: $state" }
        val behavior = writeBehaviors[nextChunkIndex]
        if (behavior is WriteBehavior.WriteFails) {
            state = State.WRITTEN
            throw behavior.throwable
        }
        writtenChunks += chunk
        state = State.WRITTEN
        return writeResults[nextChunkIndex] ?: WriteResult(chunk.rows.size.toLong(), 0, 0)
    }

    open override fun commitChunk() {
        check(state == State.WRITTEN) { "commitChunk requires WRITTEN, current state: $state" }
        state = State.OPEN
        nextChunkIndex += 1
    }

    override fun rollbackChunk() {
        check(state == State.WRITTEN) { "rollbackChunk requires WRITTEN, current state: $state" }
        state = State.OPEN
        nextChunkIndex += 1
    }

    override fun markTruncatePerformed() = Unit

    override fun finishTable(): FinishTableResult {
        check(state == State.OPEN) { "finishTable requires OPEN, current state: $state" }
        state = State.FINISHED
        return finishResult
    }

    override fun close() {
        if (state == State.CLOSED) return
        closeCount += 1
        closeHook?.invoke()
        state = State.CLOSED
    }
}
