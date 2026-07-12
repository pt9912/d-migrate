package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.data.ChunkSequence
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.chunkSchemaOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * LN-007/LN-008 (ADR 0032): the parallel branch of [TransferExecutor] —
 * FK-layer barrier, partitioned-parent fan-out, transparent-parent
 * fallback, and fail-fast error propagation. `--parallel 1` keeps the
 * sequential path (covered by [TransferExecutorCancelCheckpointTest]).
 */
class TransferExecutorParallelTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.POSTGRESQL
        override fun borrow(): DatabaseConnection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    // Reader that enforces an FK barrier: a table may only stream once every table
    // it depends on has finished. Records the tables it streamed (thread-safe).
    class BarrierReader(
        private val deps: Map<String, List<String>>,
        private val finished: MutableSet<String>,
        val streamed: MutableList<String>,
        private val failFor: String? = null,
    ) : DataReader {
        override val dialect = DatabaseDialect.POSTGRESQL
        override fun streamTable(pool: ConnectionPool, table: String, filter: DataFilter?, chunkSize: Int): ChunkSequence {
            if (table == failFor) error("boom on $table")
            val missing = deps[table].orEmpty().filter { it !in finished }
            check(missing.isEmpty()) { "$table streamed before deps $missing finished" }
            streamed.add(table)
            val chunk = DataChunk(table, listOf(ColumnDescriptor("id", nullable = true)), listOf(arrayOf<Any?>(1L)), 0)
            return object : ChunkSequence {
                override val schema: ChunkSchema = chunkSchemaOf(table, chunk.columns)
                override fun iterator() = listOf(chunk).iterator()
                override fun close() = Unit
            }
        }
    }

    class RecordingSession(private val table: String, private val finished: MutableSet<String>) : TableImportSession {
        override val targetColumns = listOf(TargetColumn("id", nullable = true, jdbcType = java.sql.Types.INTEGER, sqlTypeName = "INTEGER"))
        override fun write(chunk: DataChunk) = WriteResult(chunk.rows.size.toLong(), 0L, 0L)
        override fun commitChunk() = Unit
        override fun rollbackChunk() = Unit
        override fun markTruncatePerformed() = Unit
        override fun finishTable(): FinishTableResult {
            finished.add(table)
            return FinishTableResult.Success(emptyList())
        }
        override fun close() = Unit
    }

    class RecordingWriter(val opened: MutableList<String>, private val finished: MutableSet<String>) : DataWriter {
        override val dialect = DatabaseDialect.POSTGRESQL
        override fun schemaSync() = throw UnsupportedOperationException()
        override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions): TableImportSession {
            opened.add(table)
            return RecordingSession(table, finished)
        }
    }

    fun ctx(
        reader: DataReader,
        writer: DataWriter,
        layers: List<List<String>>,
        partitionChildren: Map<String, List<String>> = emptyMap(),
        parallelism: Int = 4,
    ) = TransferExecutionContext(
        reader = reader,
        writer = writer,
        sourcePool = pool,
        targetPool = pool,
        tables = layers.flatten(),
        filter = null,
        chunkSize = 1_000,
        importOptions = ImportOptions(),
        layers = layers,
        partitionChildren = partitionChildren,
        parallelism = parallelism,
    )

    test("FK layers run in order: a table never starts before its dependencies finished") {
        val finished = Collections.synchronizedSet(mutableSetOf<String>())
        val streamed = CopyOnWriteArrayList<String>()
        val opened = CopyOnWriteArrayList<String>()
        // A -> B -> C (A depends on B depends on C); layers put C first.
        val reader = BarrierReader(mapOf("a" to listOf("b"), "b" to listOf("c"), "c" to emptyList()), finished, streamed)
        val writer = RecordingWriter(opened, finished)
        val transferred = CopyOnWriteArrayList<String>()

        // No exception means the barrier invariant held on every worker thread.
        TransferExecutor().execute(
            ctx(reader, writer, layers = listOf(listOf("c"), listOf("b"), listOf("a"))),
        ) { transferred.add(it) }

        streamed shouldContainExactlyInAnyOrder listOf("a", "b", "c")
        transferred shouldContainExactly listOf("c", "b", "a") // per-layer completion order
    }

    test("independent tables in one layer all run (parallel siblings)") {
        val finished = Collections.synchronizedSet(mutableSetOf<String>())
        val streamed = CopyOnWriteArrayList<String>()
        val reader = BarrierReader(emptyMap(), finished, streamed)
        val writer = RecordingWriter(CopyOnWriteArrayList(), finished)

        TransferExecutor().execute(
            ctx(reader, writer, layers = listOf(listOf("t1", "t2", "t3", "t4"))),
        ) { }

        streamed shouldContainExactlyInAnyOrder listOf("t1", "t2", "t3", "t4")
    }

    test("partitioned parent fans out into per-child transfers, parent itself is not transferred") {
        val finished = Collections.synchronizedSet(mutableSetOf<String>())
        val streamed = CopyOnWriteArrayList<String>()
        val opened = CopyOnWriteArrayList<String>()
        val children = listOf("payment_p1", "payment_p2", "payment_p3")
        val reader = BarrierReader(emptyMap(), finished, streamed)
        val writer = RecordingWriter(opened, finished)
        val transferred = CopyOnWriteArrayList<String>()

        TransferExecutor().execute(
            ctx(
                reader, writer,
                layers = listOf(listOf("payment")),
                partitionChildren = mapOf("payment" to children),
            ),
        ) { transferred.add(it) }

        streamed shouldContainExactlyInAnyOrder children // children streamed, NOT "payment"
        opened shouldContainExactlyInAnyOrder children
        transferred shouldContainExactly listOf("payment") // parent signalled once, post-layer
    }

    test("no partition expansion → parent transferred as one transparent unit") {
        val finished = Collections.synchronizedSet(mutableSetOf<String>())
        val streamed = CopyOnWriteArrayList<String>()
        val reader = BarrierReader(emptyMap(), finished, streamed)
        val writer = RecordingWriter(CopyOnWriteArrayList(), finished)

        TransferExecutor().execute(
            ctx(reader, writer, layers = listOf(listOf("payment")), partitionChildren = emptyMap()),
        ) { }

        streamed shouldContainExactly listOf("payment")
    }

    test("a failing table aborts the run (fail-fast, exception propagates)") {
        val finished = Collections.synchronizedSet(mutableSetOf<String>())
        val reader = BarrierReader(emptyMap(), finished, CopyOnWriteArrayList(), failFor = "t2")
        val writer = RecordingWriter(CopyOnWriteArrayList(), finished)

        shouldThrow<IllegalStateException> {
            TransferExecutor().execute(
                ctx(reader, writer, layers = listOf(listOf("t1", "t2", "t3"))),
            ) { }
        }
    }
})
