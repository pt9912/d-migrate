package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.cancel.TestCancellationTokenSource
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ChunkSequence
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase E0.5 (2/3) checkpoint guard for [TransferExecutor]. Plan §6.4
 * acceptance: cancel before reader.streamTable, before writer.openTable,
 * between chunks, before write/commit, before finishTable, between
 * tables, before onTableTransferred — each must halt the next side
 * effect without mapping cancel to a fachlicher transfer error.
 */
class TransferExecutorCancelCheckpointTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    fun chunk(table: String, idx: Long): DataChunk = DataChunk(
        table = table,
        columns = listOf(ColumnDescriptor("id", nullable = true)),
        rows = listOf(arrayOf<Any?>(idx)),
        chunkIndex = idx,
    )

    class CountingReader(private val chunksByTable: Map<String, List<DataChunk>>) : DataReader {
        override val dialect = DatabaseDialect.SQLITE
        val streamCalls = AtomicInteger(0)
        override fun streamTable(
            pool: ConnectionPool, table: String,
            filter: dev.dmigrate.core.data.DataFilter?, chunkSize: Int,
        ): ChunkSequence {
            streamCalls.incrementAndGet()
            val chunks = chunksByTable[table] ?: emptyList()
            return object : ChunkSequence {
                override fun iterator() = chunks.iterator()
                override fun close() = Unit
            }
        }
    }

    open class CountingSession(targetCols: List<TargetColumn>) : TableImportSession {
        override val targetColumns: List<TargetColumn> = targetCols
        val writeCalls = AtomicInteger(0)
        val commitCalls = AtomicInteger(0)
        val finishCalls = AtomicInteger(0)
        override fun write(chunk: DataChunk): WriteResult {
            writeCalls.incrementAndGet()
            return WriteResult(chunk.rows.size.toLong(), 0L, 0L)
        }
        override fun commitChunk() { commitCalls.incrementAndGet() }
        override fun rollbackChunk() = Unit
        override fun markTruncatePerformed() = Unit
        override fun finishTable(): FinishTableResult {
            finishCalls.incrementAndGet()
            return FinishTableResult.Success(emptyList())
        }
        override fun close() = Unit
    }

    class CountingWriter(private val sessions: Map<String, CountingSession>) : DataWriter {
        override val dialect = DatabaseDialect.SQLITE
        val openCalls = AtomicInteger(0)
        override fun schemaSync() = throw UnsupportedOperationException()
        override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions): TableImportSession {
            openCalls.incrementAndGet()
            return sessions[table] ?: error("no session for $table")
        }
    }

    val targetCol = TargetColumn("id", nullable = true, jdbcType = java.sql.Types.INTEGER, sqlTypeName = "INTEGER")

    fun ctx(
        reader: DataReader,
        writer: DataWriter,
        tables: List<String>,
        token: CancellationToken,
    ) = TransferExecutionContext(
        reader = reader,
        writer = writer,
        sourcePool = pool,
        targetPool = pool,
        tables = tables,
        filter = null,
        chunkSize = 1_000,
        importOptions = ImportOptions(),
        cancellationToken = token,
    )

    test("cancel before reader.streamTable starts no stream") {
        val reader = CountingReader(mapOf("t1" to listOf(chunk("t1", 0))))
        val sessions = mapOf("t1" to CountingSession(listOf(targetCol)))
        val writer = CountingWriter(sessions)
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(0) }

        shouldThrow<OperationCancelledException> {
            TransferExecutor().execute(ctx(reader, writer, listOf("t1"), source.token)) { }
        }
        reader.streamCalls.get() shouldBe 0
    }

    test("cancel before writer.openTable starts no session open") {
        val reader = CountingReader(mapOf("t1" to listOf(chunk("t1", 0))))
        val sessions = mapOf("t1" to CountingSession(listOf(targetCol)))
        val writer = CountingWriter(sessions)
        // Sequence in execute() + transferTable():
        //   #1 outer table-loop top
        //   #2 transferTable entry — before reader.streamTable
        //   #3 inside `use { sequence -> ` — before writer.openTable
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(2) }

        shouldThrow<OperationCancelledException> {
            TransferExecutor().execute(ctx(reader, writer, listOf("t1"), source.token)) { }
        }
        reader.streamCalls.get() shouldBe 1
        writer.openCalls.get() shouldBe 0
    }

    test("cancel between chunks starts no further write") {
        val reader = CountingReader(mapOf("t1" to listOf(chunk("t1", 0), chunk("t1", 1))))
        val source = TestCancellationTokenSource()
        val signalingSession = object : CountingSession(listOf(targetCol)) {
            override fun commitChunk() {
                super.commitChunk()
                // After chunk 0's commit, cancel. The pre-normalize check
                // at the top of the next iteration fires before chunk 1 writes.
                if (commitCalls.get() == 1) source.cancel("after-first-commit")
            }
        }
        val writer = CountingWriter(mapOf("t1" to signalingSession))

        shouldThrow<OperationCancelledException> {
            TransferExecutor().execute(ctx(reader, writer, listOf("t1"), source.token)) { }
        }
        signalingSession.writeCalls.get() shouldBe 1
        signalingSession.commitCalls.get() shouldBe 1
        signalingSession.finishCalls.get() shouldBe 0
    }

    test("cancel before finishTable starts no finish") {
        val reader = CountingReader(mapOf("t1" to listOf(chunk("t1", 0))))
        val source = TestCancellationTokenSource()
        val session = object : CountingSession(listOf(targetCol)) {
            override fun commitChunk() {
                super.commitChunk()
                // After the only chunk's commit, cancel. The pre-finish
                // checkpoint then fires before finishTable().
                source.cancel("before-finish")
            }
        }
        val writer = CountingWriter(mapOf("t1" to session))

        shouldThrow<OperationCancelledException> {
            TransferExecutor().execute(ctx(reader, writer, listOf("t1"), source.token)) { }
        }
        session.finishCalls.get() shouldBe 0
    }

    test("cancel between tables starts no further table") {
        val reader = CountingReader(
            mapOf(
                "t1" to listOf(chunk("t1", 0)),
                "t2" to listOf(chunk("t2", 0)),
            ),
        )
        val s1 = CountingSession(listOf(targetCol))
        val s2 = CountingSession(listOf(targetCol))
        val writer = CountingWriter(mapOf("t1" to s1, "t2" to s2))
        // outer execute() has 2 checks per iteration (top-of-loop, before-onTableTransferred).
        // For 1 full iteration (t1) we want #1 (top) → pass, t1 runs, #N before
        // onTableTransferred → pass, then #M next iteration (t2) → throw.
        // Counting all checkpoints inside transferTable(t1):
        //   #1 outer top of t1
        //   #2 transferTable entry (before streamTable)
        //   #3 before openTable
        //   #4 chunk-loop pre-normalize (chunk 0)
        //   #5 before write
        //   #6 before commit
        //   #7 before finishTable
        //   #8 outer before onTableTransferred (for t1)
        //   #9 outer top of t2 — SHOULD THROW
        val transferred = mutableListOf<String>()
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(8) }

        shouldThrow<OperationCancelledException> {
            TransferExecutor().execute(
                ctx(reader, writer, listOf("t1", "t2"), source.token)
            ) { table -> transferred += table }
        }
        s1.writeCalls.get() shouldBe 1
        s1.commitCalls.get() shouldBe 1
        s1.finishCalls.get() shouldBe 1
        s2.writeCalls.get() shouldBe 0
        // onTableTransferred for t1 ran (checkpoint #8 fires AFTER it
        // returns and BEFORE the next iteration's top), so transferred
        // contains exactly t1.
        transferred shouldBe listOf("t1")
    }

    test("default token transfers all tables") {
        val reader = CountingReader(
            mapOf(
                "t1" to listOf(chunk("t1", 0)),
                "t2" to listOf(chunk("t2", 0)),
            ),
        )
        val s1 = CountingSession(listOf(targetCol))
        val s2 = CountingSession(listOf(targetCol))
        val writer = CountingWriter(mapOf("t1" to s1, "t2" to s2))
        val transferred = mutableListOf<String>()

        TransferExecutor().execute(
            ctx(reader, writer, listOf("t1", "t2"), CancellationToken.none())
        ) { table -> transferred += table }

        transferred shouldBe listOf("t1", "t2")
        s1.finishCalls.get() shouldBe 1
        s2.finishCalls.get() shouldBe 1
    }
})
