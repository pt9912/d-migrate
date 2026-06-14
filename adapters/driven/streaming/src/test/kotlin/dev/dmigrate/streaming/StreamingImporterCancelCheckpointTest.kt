package dev.dmigrate.streaming

import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.cancel.TestCancellationTokenSource
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.DataChunkReaderFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.SeekableDataChunkReaderFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.InputStream
import java.nio.file.Files
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger

/**
 * LN-010 / LN-011 checkpoint guard for [StreamingImporter] — the table-level
 * outer loop. LF-010 / LF-013 / LN-012 acceptance:
 * - cancel before RunStarted → no progress event, no table imports
 * - cancel between tables → first table imports, second does not start
 * - cancel before onTableCompleted → table imports, callback skipped
 */
class StreamingImporterCancelCheckpointTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): Connection = error("unused")
        override fun activeConnections() = 0
        override fun close() {}
    }

    val emptyReaderFactory = object : DataChunkReaderFactory {
        override fun create(
            format: DataExportFormat,
            input: InputStream,
            table: String,
            chunkSize: Int,
            options: FormatReadOptions,
        ): DataChunkReader = object : DataChunkReader {
            override fun nextChunk() = null
            override fun headerColumns(): List<String>? = null
            override fun close() = input.close()
        }
    }

    val noopSession = object : TableImportSession {
        override val targetColumns: List<TargetColumn> = emptyList()
        override fun write(chunk: dev.dmigrate.core.data.DataChunk) = WriteResult(0L, 0L, 0L)
        override fun commitChunk() = Unit
        override fun rollbackChunk() = Unit
        override fun markTruncatePerformed() = Unit
        override fun finishTable(): FinishTableResult = FinishTableResult.Success(emptyList())
        override fun close() = Unit
    }

    val writerLookup: (DatabaseDialect) -> DataWriter = {
        object : DataWriter {
            override val dialect = DatabaseDialect.SQLITE
            override fun schemaSync() = throw UnsupportedOperationException()
            override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions) = noopSession
        }
    }

    class CountingTableImporter : TableImporter(emptyReaderFactory, { _, _ -> }) {
        val imports = AtomicInteger(0)
        override fun import(params: TableImportParams): TableImportSummary {
            imports.incrementAndGet()
            return TableImportSummary(
                table = params.tableInput.table,
                rowsInserted = 0L,
                rowsUpdated = 0L,
                rowsSkipped = 0L,
                rowsUnknown = 0L,
                rowsFailed = 0L,
                chunkFailures = emptyList(),
                sequenceAdjustments = emptyList(),
                targetColumns = emptyList<ColumnDescriptor>(),
                triggerMode = TriggerMode.FIRE,
                failedFinish = null,
                error = null,
                durationMs = 0L,
            )
        }
    }

    fun directoryWithTables(vararg tables: String): java.nio.file.Path {
        val dir = Files.createTempDirectory("e0-5-stream")
        for (t in tables) {
            Files.writeString(dir.resolve("$t.json"), "[]")
        }
        return dir
    }

    test("cancel before RunStarted skips RunStarted and all table imports") {
        val capturer = CountingTableImporter()
        val reportEvents = AtomicInteger(0)
        val reporter = ProgressReporter { reportEvents.incrementAndGet() }
        val importer = StreamingImporter(
            readerFactory = emptyReaderFactory,
            writerLookup = writerLookup,
        ).also { it.tableImporter = capturer }

        val dir = directoryWithTables("users", "orders")
        val source = TestCancellationTokenSource().also { it.cancelAfterCheckpoints(0) }
        try {
            shouldThrow<OperationCancelledException> {
                importer.import(
                    pool = pool,
                    input = ImportInput.Directory(path = dir),
                    format = DataExportFormat.JSON,
                    progressReporter = reporter,
                    cancellationToken = source.token,
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }

        reportEvents.get() shouldBe 0
        capturer.imports.get() shouldBe 0
    }

    test("cancel between tables starts no further table import") {
        val capturer = CountingTableImporter()
        val completedTables = mutableListOf<String>()
        val source = TestCancellationTokenSource()
        val importer = StreamingImporter(
            readerFactory = emptyReaderFactory,
            writerLookup = writerLookup,
        ).also { it.tableImporter = capturer }

        // Trigger cancel after the first table imports. The loop-top
        // check on the second iteration must observe and throw.
        val capturerWithSignal = object : TableImporter(emptyReaderFactory, { _, _ -> }) {
            override fun import(params: TableImportParams): TableImportSummary {
                capturer.imports.incrementAndGet()
                source.cancel("after-first-table")
                return TableImportSummary(
                    table = params.tableInput.table,
                    rowsInserted = 0L,
                    rowsUpdated = 0L,
                    rowsSkipped = 0L,
                    rowsUnknown = 0L,
                    rowsFailed = 0L,
                    chunkFailures = emptyList(),
                    sequenceAdjustments = emptyList(),
                    targetColumns = emptyList<ColumnDescriptor>(),
                    triggerMode = TriggerMode.FIRE,
                    failedFinish = null,
                    error = null,
                    durationMs = 0L,
                )
            }
        }
        importer.tableImporter = capturerWithSignal

        val dir = directoryWithTables("a", "b")
        try {
            shouldThrow<OperationCancelledException> {
                importer.import(
                    pool = pool,
                    input = ImportInput.Directory(path = dir),
                    format = DataExportFormat.JSON,
                    onTableCompleted = { completedTables += it.table },
                    cancellationToken = source.token,
                )
            }
        } finally {
            dir.toFile().deleteRecursively()
        }

        capturer.imports.get() shouldBe 1
        // Cancel fired between commitChunk and onTableCompleted —
        // completion callback for the first table is also gated.
        completedTables shouldBe emptyList()
    }
})
