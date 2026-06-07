package dev.dmigrate.streaming

import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.cancel.TestCancellationTokenSource
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataExportFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.sql.Types
import java.util.concurrent.atomic.AtomicInteger

/**
 * LN-010 / LN-011 checkpoint guard for the per-table import path:
 * cancel before reader/writer open, cancel during resume-skip, cancel
 * before each chunk write/commit/onChunkCommitted, cancel before
 * `finishTable`. LF-010 / LF-013 / LN-012 acceptance.
 */
class TableImporterCancelCheckpointTest : FunSpec({

    val targetColumns = listOf(
        TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
        TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
    )

    fun chunk(table: String, chunkIndex: Long, rows: List<Array<Any?>>) = DataChunk(
        table = table,
        columns = listOf(
            ColumnDescriptor("id", nullable = true),
            ColumnDescriptor("name", nullable = true),
        ),
        rows = rows,
        chunkIndex = chunkIndex,
    )

    fun chunks(count: Int) = (0 until count).map { idx ->
        chunk("users", idx.toLong(), listOf(arrayOf<Any?>(idx.toLong(), "row-$idx")))
    }

    fun params(
        session: FakeTableImportSession,
        token: dev.dmigrate.core.cancel.CancellationToken,
        resumeState: ImportTableResumeState? = null,
    ) = TableImportParams(
        pool = ImporterNoopConnectionPool,
        writer = FakeWriter(mapOf("users" to session)),
        tableInput = ResolvedTableInput.Stream("users") { ByteArrayInputStream("[]".toByteArray()) },
        format = DataExportFormat.JSON,
        options = ImportOptions(),
        config = PipelineConfig(chunkSize = 100),
        reporter = NoOpProgressReporter,
        ordinal = 1,
        tableCount = 1,
        resumeState = resumeState,
        onChunkCommitted = { },
        cancellationToken = token,
    )

    test("cancel before reader.create skips reader allocation entirely") {
        val readerFactory = FakeReaderFactory(emptyMap())
        val session = FakeTableImportSession(targetColumns = targetColumns)
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(0) }
        val importer = TableImporter(readerFactory, onTableOpened = { _, _ -> })

        shouldThrow<OperationCancelledException> {
            importer.import(params(session, source.token))
        }
        readerFactory.createdTables.size shouldBe 0
    }

    test("cancel before writer.openTable skips session open") {
        val reader = FakeReader(header = listOf("id", "name"), chunks = emptyList())
        val readerFactory = FakeReaderFactory(mapOf("users" to reader))
        val session = FakeTableImportSession(targetColumns = targetColumns)
        // Checkpoint sequence in TableImporter.import + prepareImport:
        //   #1 import-entry (top of import())
        //   #2 prepareImport — before readerFactory.create
        //   #3 prepareImport — before writer.openTable
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(2) }
        val importer = TableImporter(readerFactory, onTableOpened = { _, _ -> })

        shouldThrow<OperationCancelledException> {
            importer.import(params(session, source.token))
        }
        readerFactory.createdTables.size shouldBe 1
        session.closeCount shouldBe 0
    }

    test("cancel during resume-skip starts no further nextChunk and no write") {
        val reader = CountingReader(
            header = listOf("id", "name"),
            chunks = chunks(5),
        )
        val readerFactory = FakeReaderFactory(mapOf("users" to reader))
        val session = FakeTableImportSession(targetColumns = targetColumns)
        // Resume-skip iterates 3 times before our test wants to cancel.
        // Sequence so far:
        //   #1 import entry
        //   #2 readerFactory.create
        //   #3 writer.openTable
        //   #4 reporter.report(ImportTableStarted)
        //   #5..N skipCommittedChunks pre-read checks (one per skipped chunk)
        // For offset=3 we want cancel after 2 skips:
        //   #5 first skip pre-read
        //   #6 second skip pre-read
        //   #7 third skip pre-read — should THROW
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(6) }
        val importer = TableImporter(readerFactory, onTableOpened = { _, _ -> })

        shouldThrow<OperationCancelledException> {
            importer.import(
                params(session, source.token,
                    resumeState = ImportTableResumeState(committedChunks = 3)),
            )
        }
        // Two skips ran (nextChunk twice); the third was halted before its read.
        reader.nextChunkCalls.get() shouldBe 2
        session.writtenChunks.size shouldBe 0
    }

    test("cancel between chunks starts no further write") {
        val reader = FakeReader(header = listOf("id", "name"), chunks = chunks(3))
        val readerFactory = FakeReaderFactory(mapOf("users" to reader))
        val session = FakeTableImportSession(targetColumns = targetColumns)
        // Wire the source so cancel fires AFTER chunk #0 commits, BEFORE
        // chunk #1's loop-top check writes anything. The session lets us
        // observe the exact transition point.
        val source = TestCancellationTokenSource()
        val countingSession = object : FakeTableImportSession(targetColumns = targetColumns) {
            var commits = 0
            override fun commitChunk() {
                super.commitChunk()
                commits += 1
                if (commits == 1) source.cancel("after-first-commit")
            }
        }
        val readerFactory2 = FakeReaderFactory(mapOf("users" to reader))
        val importer = TableImporter(readerFactory2, onTableOpened = { _, _ -> })

        shouldThrow<OperationCancelledException> {
            importer.import(params(countingSession, source.token))
        }
        countingSession.writtenChunks.size shouldBe 1
        countingSession.commits shouldBe 1
    }

    test("cancel before finishTable starts no finish") {
        val reader = FakeReader(header = listOf("id", "name"), chunks = chunks(1))
        val readerFactory = FakeReaderFactory(mapOf("users" to reader))
        val finishCount = AtomicInteger(0)
        val source = TestCancellationTokenSource()
        val session = object : FakeTableImportSession(targetColumns = targetColumns) {
            override fun commitChunk() {
                super.commitChunk()
                // After the only chunk commits, request cancel. The next
                // loop iteration's reader.nextChunk() returns null and
                // exits the chunk loop normally; the import() body then
                // hits the pre-finish checkpoint which observes cancel.
                source.cancel("before-finish")
            }
            override fun finishTable(): dev.dmigrate.driver.data.FinishTableResult {
                finishCount.incrementAndGet()
                return super.finishTable()
            }
        }
        val importer = TableImporter(readerFactory, onTableOpened = { _, _ -> })

        shouldThrow<OperationCancelledException> {
            importer.import(params(session, source.token))
        }
        finishCount.get() shouldBe 0
    }

    test("default token completes the table without throwing") {
        val reader = FakeReader(header = listOf("id", "name"), chunks = chunks(2))
        val readerFactory = FakeReaderFactory(mapOf("users" to reader))
        val session = FakeTableImportSession(targetColumns = targetColumns)
        val importer = TableImporter(readerFactory, onTableOpened = { _, _ -> })

        val summary = importer.import(
            params(session, dev.dmigrate.core.cancel.CancellationToken.none()),
        )

        summary.rowsInserted shouldBe 2L
        session.writtenChunks.size shouldBe 2
    }
})

/** Variant of [FakeReader] that counts `nextChunk` calls so resume-skip
 *  cancellation can be asserted at the call-site granularity. */
private class CountingReader(
    header: List<String>?,
    chunks: List<DataChunk>,
) : FakeReader(header = header, chunks = chunks) {
    val nextChunkCalls = AtomicInteger(0)
    override fun consumeNextChunk(): DataChunk? {
        nextChunkCalls.incrementAndGet()
        return super.consumeNextChunk()
    }
}
