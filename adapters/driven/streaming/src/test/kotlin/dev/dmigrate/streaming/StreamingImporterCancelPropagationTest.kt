package dev.dmigrate.streaming

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.InputStream
import java.nio.file.Files
import java.sql.Connection
import java.util.concurrent.atomic.AtomicReference

/**
 * LN-010 / LN-011 propagation guard: a token passed to [StreamingImporter.import]
 * must reach the chunk-loop boundary as [TableImportParams.cancellationToken]
 * — not be lost at the runner facade. LN-010 / LN-011 will use the same field
 * inside [TableImporter.import] to gate side effects.
 */
class StreamingImporterCancelPropagationTest : FunSpec({

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
            override fun nextChunk(): DataChunk? = null
            override fun headerColumns(): List<String>? = null
            override fun close() = input.close()
        }
    }

    val noopSession = object : TableImportSession {
        override val targetColumns: List<TargetColumn> = emptyList()
        override fun write(chunk: DataChunk) = WriteResult(0L, 0L, 0L)
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

    class CapturingTableImporter : TableImporter(emptyReaderFactory, { _, _ -> }) {
        val captured = AtomicReference<CancellationToken?>(null)
        override fun import(params: TableImportParams): TableImportSummary {
            captured.set(params.cancellationToken)
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

    test("cancellationToken passed to import lands on TableImportParams") {
        val capturer = CapturingTableImporter()
        val importer = StreamingImporter(
            readerFactory = emptyReaderFactory,
            seekableReaderFactory = UnsupportedSeekableDataChunkReaderFactory("test"),
            writerLookup = writerLookup,
        ).also { it.tableImporter = capturer }

        val tmpFile = Files.createTempFile("e0-3-prop", ".jsonl").also { Files.writeString(it, "") }
        val token = CancellationTokenSource.create().token
        try {
            importer.import(
                pool = pool,
                input = ImportInput.SingleFile(table = "users", path = tmpFile),
                format = DataExportFormat.JSON,
                cancellationToken = token,
            )
        } finally {
            Files.deleteIfExists(tmpFile)
        }

        (capturer.captured.get() === token) shouldBe true
    }

    test("default cancellationToken is none() when caller omits it") {
        val capturer = CapturingTableImporter()
        val importer = StreamingImporter(
            readerFactory = emptyReaderFactory,
            seekableReaderFactory = UnsupportedSeekableDataChunkReaderFactory("test"),
            writerLookup = writerLookup,
        ).also { it.tableImporter = capturer }

        val tmpFile = Files.createTempFile("e0-3-prop", ".jsonl").also { Files.writeString(it, "") }
        try {
            importer.import(
                pool = pool,
                input = ImportInput.SingleFile(table = "users", path = tmpFile),
                format = DataExportFormat.JSON,
            )
        } finally {
            Files.deleteIfExists(tmpFile)
        }

        capturer.captured.get()!!.isCancellationRequested shouldBe false
    }
})
