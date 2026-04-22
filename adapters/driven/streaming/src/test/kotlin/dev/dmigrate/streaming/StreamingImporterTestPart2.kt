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
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.DataChunkReaderFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.sql.Connection
import java.sql.Types
import kotlin.io.path.deleteIfExists

class StreamingImporterTestPart2 : FunSpec({

    val pool = ImporterNoopConnectionPool
    val targetColumns = listOf(
        TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
        TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
    )

    fun chunk(
        table: String,
        columns: List<String>,
        rows: List<Array<Any?>>,
        chunkIndex: Long = 0,
    ) = DataChunk(
        table = table,
        columns = columns.map { ColumnDescriptor(it, nullable = true) },
        rows = rows,
        chunkIndex = chunkIndex,
    )


    test("write failure aborts on onError abort") {
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf(
                "users" to FakeReader(
                    header = listOf("id"),
                    chunks = listOf(chunk("users", listOf("id"), listOf(arrayOf<Any?>(1L)))),
                )
            )
        )
        val session = FakeTableImportSession(
            targetColumns = listOf(targetColumns.first()),
            writeBehaviors = mapOf(0L to FakeTableImportSession.WriteBehavior.WriteFails(RuntimeException("boom"))),
        )
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )
        val file = Files.createTempFile("streaming-import-", ".json")
        try {
            shouldThrow<RuntimeException> {
                importer.import(
                    pool = pool,
                    input = ImportInput.SingleFile("users", file),
                    format = DataExportFormat.JSON,
                    options = ImportOptions(onError = OnError.ABORT),
                )
            }
        } finally {
            file.deleteIfExists()
        }
    }

    test("write failure skips chunk and counts rowsFailed on onError skip") {
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf(
                "users" to FakeReader(
                    header = listOf("id"),
                    chunks = listOf(
                        chunk("users", listOf("id"), listOf(arrayOf<Any?>(1L), arrayOf<Any?>(2L)), 0),
                        chunk("users", listOf("id"), listOf(arrayOf<Any?>(3L)), 1),
                    ),
                )
            )
        )
        val session = FakeTableImportSession(
            targetColumns = listOf(targetColumns.first()),
            writeBehaviors = mapOf(0L to FakeTableImportSession.WriteBehavior.WriteFails(RuntimeException("boom"))),
        )
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )
        val file = Files.createTempFile("streaming-import-", ".json")
        try {
            val result = importer.import(
                pool = pool,
                input = ImportInput.SingleFile("users", file),
                format = DataExportFormat.JSON,
                options = ImportOptions(onError = OnError.SKIP),
            )
            val summary = result.tables.single()
            summary.rowsFailed shouldBe 2
            summary.rowsInserted shouldBe 1
            summary.chunkFailures shouldHaveSize 0
        } finally {
            file.deleteIfExists()
        }
    }

    test("write failure logs chunk failure on onError log") {
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf(
                "users" to FakeReader(
                    header = listOf("id"),
                    chunks = listOf(
                        chunk("users", listOf("id"), listOf(arrayOf<Any?>(1L), arrayOf<Any?>(2L)), 0),
                        chunk("users", listOf("id"), listOf(arrayOf<Any?>(3L)), 1),
                    ),
                )
            )
        )
        val session = FakeTableImportSession(
            targetColumns = listOf(targetColumns.first()),
            writeBehaviors = mapOf(0L to FakeTableImportSession.WriteBehavior.WriteFails(RuntimeException("boom"))),
        )
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )
        val file = Files.createTempFile("streaming-import-", ".json")
        try {
            val result = importer.import(
                pool = pool,
                input = ImportInput.SingleFile("users", file),
                format = DataExportFormat.JSON,
                options = ImportOptions(onError = OnError.LOG),
            )
            val summary = result.tables.single()
            summary.rowsFailed shouldBe 2
            summary.chunkFailures.shouldHaveSize(1)
            summary.chunkFailures.single().chunkIndex shouldBe 0
        } finally {
            file.deleteIfExists()
        }
    }

    test("late nextChunk failure logs table error and continues with next table") {
        val dir = Files.createTempDirectory("streaming-import-dir-")
        try {
            Files.writeString(dir.resolve("a.json"), "[]")
            Files.writeString(dir.resolve("b.json"), "[]")
            val readerFactory = FakeReaderFactory(
                readersByTable = mapOf(
                    "a" to FakeReader(
                        header = listOf("id"),
                        chunks = listOf(chunk("a", listOf("id"), listOf(arrayOf<Any?>(1L)), 0)),
                        nextChunkFailures = mapOf(1 to RuntimeException("bad row in second chunk")),
                    ),
                    "b" to FakeReader(
                        header = listOf("id"),
                        chunks = listOf(chunk("b", listOf("id"), listOf(arrayOf<Any?>(2L)), 0)),
                    ),
                )
            )
            val importer = StreamingImporter(
                readerFactory = readerFactory,
                writerLookup = {
                    FakeWriter(
                        mapOf(
                            "a" to FakeTableImportSession(targetColumns = listOf(targetColumns.first())),
                            "b" to FakeTableImportSession(targetColumns = listOf(targetColumns.first())),
                        )
                    )
                },
            )

            val result = importer.import(
                pool = pool,
                input = ImportInput.Directory(dir),
                format = DataExportFormat.JSON,
                options = ImportOptions(onError = OnError.LOG),
            )

            result.tables.shouldHaveSize(2)
            val failed = result.tables.first { it.table == "a" }
            failed.rowsInserted shouldBe 1
            failed.error shouldBe "bad row in second chunk"
            failed.chunkFailures.shouldHaveSize(1)
            failed.chunkFailures.single() shouldBe ChunkFailure(
                table = "a",
                chunkIndex = 1,
                rowsLost = 0,
                reason = "bad row in second chunk",
            )

            val successful = result.tables.first { it.table == "b" }
            successful.rowsInserted shouldBe 1
            successful.error shouldBe null
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    test("finish partial failure is transferred into failedFinish info") {
        val first = RuntimeException("first enable failed")
        val second = RuntimeException("second enable failed")
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf("users" to FakeReader(header = listOf("id"), chunks = emptyList()))
        )
        val session = FakeTableImportSession(
            targetColumns = listOf(targetColumns.first()),
            finishResult = FinishTableResult.PartialFailure(
                adjustments = listOf(SequenceAdjustment("users", "id", null, 2)),
                cause = first,
            ),
            closeHook = {
                first.addSuppressed(second)
            },
        )
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )
        val file = Files.createTempFile("streaming-import-", ".json")
        try {
            val result = importer.import(
                pool = pool,
                input = ImportInput.SingleFile("users", file),
                format = DataExportFormat.JSON,
            )
            val failedFinish = result.tables.single().failedFinish
            failedFinish shouldBe FailedFinishInfo(
                adjustments = listOf(SequenceAdjustment("users", "id", null, 2)),
                causeMessage = "first enable failed",
                causeClass = first::class.qualifiedName ?: first.javaClass.name,
                causeStack = first.stackTraceToString(),
                closeCauseMessage = "second enable failed",
                closeCauseClass = second::class.qualifiedName ?: second.javaClass.name,
                closeCauseStack = second.stackTraceToString(),
            )
        } finally {
            file.deleteIfExists()
        }
    }

    test("rowsSkipped and rowsFailed stay separate") {
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf(
                "users" to FakeReader(
                    header = listOf("id"),
                    chunks = listOf(
                        chunk("users", listOf("id"), listOf(arrayOf<Any?>(1L), arrayOf<Any?>(2L)), 0),
                        chunk("users", listOf("id"), listOf(arrayOf<Any?>(3L), arrayOf<Any?>(4L)), 1),
                    ),
                )
            )
        )
        val session = FakeTableImportSession(
            targetColumns = listOf(targetColumns.first()),
            writeResults = mapOf(0L to WriteResult(rowsInserted = 0, rowsUpdated = 0, rowsSkipped = 2)),
            writeBehaviors = mapOf(1L to FakeTableImportSession.WriteBehavior.WriteFails(RuntimeException("boom"))),
        )
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )
        val file = Files.createTempFile("streaming-import-", ".json")
        try {
            val result = importer.import(
                pool = pool,
                input = ImportInput.SingleFile("users", file),
                format = DataExportFormat.JSON,
                options = ImportOptions(onError = OnError.SKIP),
            )
            val summary = result.tables.single()
            summary.rowsSkipped shouldBe 2
            summary.rowsFailed shouldBe 2
        } finally {
            file.deleteIfExists()
        }
    }

    test("rowsUnknown is aggregated without becoming rowsInserted") {
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf(
                "users" to FakeReader(
                    header = listOf("id"),
                    chunks = listOf(chunk("users", listOf("id"), listOf(arrayOf<Any?>(1L), arrayOf<Any?>(2L)), 0)),
                )
            )
        )
        val session = FakeTableImportSession(
            targetColumns = listOf(targetColumns.first()),
            writeResults = mapOf(0L to WriteResult(rowsInserted = 0, rowsUpdated = 0, rowsSkipped = 0, rowsUnknown = 2)),
        )
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )
        val file = Files.createTempFile("streaming-import-", ".json")
        try {
            val result = importer.import(
                pool = pool,
                input = ImportInput.SingleFile("users", file),
                format = DataExportFormat.JSON,
            )
            val summary = result.tables.single()
            summary.rowsInserted shouldBe 0
            summary.rowsUnknown shouldBe 2
        } finally {
            file.deleteIfExists()
        }
    }

    test("close runs even after finish partial failure") {
        val cause = RuntimeException("finish failed")
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf("users" to FakeReader(header = listOf("id"), chunks = emptyList()))
        )
        val session = FakeTableImportSession(
            targetColumns = listOf(targetColumns.first()),
            finishResult = FinishTableResult.PartialFailure(emptyList(), cause),
        )
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )
        val file = Files.createTempFile("streaming-import-", ".json")
        try {
            importer.import(
                pool = pool,
                input = ImportInput.SingleFile("users", file),
                format = DataExportFormat.JSON,
            )
            session.closeCount shouldBe 1
        } finally {
            file.deleteIfExists()
        }
    }

    test("reader close failure still closes session") {
        val closeFailure = RuntimeException("reader close failed")
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf(
                "users" to FakeReader(
                    header = listOf("id"),
                    chunks = emptyList(),
                    closeThrowable = closeFailure,
                )
            )
        )
        val session = FakeTableImportSession(targetColumns = listOf(targetColumns.first()))
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )
        val file = Files.createTempFile("streaming-import-", ".json")
        try {
            val ex = shouldThrow<RuntimeException> {
                importer.import(
                    pool = pool,
                    input = ImportInput.SingleFile("users", file),
                    format = DataExportFormat.JSON,
                )
            }

            ex.message shouldBe "reader close failed"
            session.closeCount shouldBe 1
        } finally {
            file.deleteIfExists()
        }
    }

})
