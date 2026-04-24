package dev.dmigrate.driver.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * Single-use [ChunkSequence] ueber ein offenes JDBC-[ResultSet].
 *
 * Garantiert §6.17: emittiert immer mindestens einen Chunk mit den
 * `columns` aus dem [ResultSetMetaData], auch wenn das ResultSet leer ist
 * (`rows = emptyList()`, `chunkIndex = 0`).
 *
 * `close()` fuehrt rollback + autoCommit-Reset + conn.close() aus
 * (siehe §6.12) und ist idempotent.
 */
internal class JdbcChunkSequence(
    private val table: String,
    private val rs: ResultSet,
    private val stmt: PreparedStatement,
    private val conn: Connection,
    private val savedAutoCommit: Boolean,
    private val chunkSize: Int,
) : ChunkSequence {

    private val log = LoggerFactory.getLogger(JdbcChunkSequence::class.java)
    private var iteratorRequested = false
    private var closed = false

    override fun iterator(): Iterator<DataChunk> {
        check(!iteratorRequested) {
            "ChunkSequence already consumed; JDBC ResultSet cannot be re-iterated"
        }
        check(!closed) {
            "ChunkSequence is closed"
        }
        iteratorRequested = true
        return JdbcChunkIterator()
    }

    override fun close() {
        if (closed) return
        closed = true
        tryClose { rs.close() }
        tryClose { stmt.close() }
        tryClose { conn.rollback() }
        tryClose { conn.autoCommit = savedAutoCommit }
        tryClose { conn.close() }
    }

    private inline fun tryClose(action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            log.debug("Cleanup failure while closing ChunkSequence for table '{}': {}", table, t.message)
        }
    }

    private inner class JdbcChunkIterator : Iterator<DataChunk> {
        private val columns: List<ColumnDescriptor> = readColumnMetadata()
        private val columnCount: Int = columns.size
        private var chunkIndex: Long = 0
        private var nextChunk: DataChunk? = null
        private var firstChunkEmitted = false
        private var rsExhausted = false

        init {
            prepareNext()
        }

        override fun hasNext(): Boolean = nextChunk != null

        override fun next(): DataChunk {
            val chunk = nextChunk ?: throw NoSuchElementException("ChunkSequence exhausted")
            firstChunkEmitted = true
            prepareNext()
            if (nextChunk == null) {
                close()
            }
            return chunk
        }

        private fun prepareNext() {
            if (rsExhausted) {
                nextChunk = null
                return
            }
            val rows = ArrayList<Array<Any?>>(chunkSize)
            while (rows.size < chunkSize && rs.next()) {
                val row = arrayOfNulls<Any?>(columnCount)
                for (index in 0 until columnCount) {
                    row[index] = rs.getObject(index + 1)
                }
                rows += row
            }
            if (rows.size < chunkSize) {
                rsExhausted = true
            }
            if (rows.isEmpty() && firstChunkEmitted) {
                nextChunk = null
                return
            }
            nextChunk = DataChunk(
                table = table,
                columns = columns,
                rows = rows,
                chunkIndex = chunkIndex++,
            )
        }

        private fun readColumnMetadata(): List<ColumnDescriptor> {
            val metadata = rs.metaData
            val count = metadata.columnCount
            val columns = ArrayList<ColumnDescriptor>(count)
            for (index in 1..count) {
                columns += ColumnDescriptor(
                    name = metadata.getColumnLabel(index),
                    nullable = metadata.isNullable(index) != java.sql.ResultSetMetaData.columnNoNulls,
                    sqlTypeName = runCatching { metadata.getColumnTypeName(index) }.getOrNull(),
                )
            }
            return columns
        }
    }
}
