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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.sql.Connection
import java.sql.Types
import kotlin.io.path.deleteIfExists

class StreamingImporterTest : FunSpec({

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

    test("imports single table from single file input") {
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf(
                "users" to FakeReader(
                    header = listOf("name", "id"),
                    chunks = listOf(
                        chunk(
                            table = "users",
                            columns = listOf("name", "id"),
                            rows = listOf(arrayOf<Any?>("alice", 1L), arrayOf<Any?>("bob", 2L)),
                        )
                    )
                )
            )
        )
        val session = FakeTableImportSession(targetColumns = targetColumns)
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

            result.success shouldBe true
            result.tables.shouldHaveSize(1)
            val summary = result.tables.single()
            summary.rowsInserted shouldBe 2
            summary.rowsUpdated shouldBe 0
            summary.rowsSkipped shouldBe 0
            summary.rowsUnknown shouldBe 0
            summary.rowsFailed shouldBe 0
            session.writtenChunks.shouldHaveSize(1)
            session.writtenChunks.single().columns.map { it.name } shouldContainExactly listOf("id", "name")
            session.writtenChunks.single().rows.map { it.toList() } shouldContainExactly listOf(
                listOf(1L, "alice"),
                listOf(2L, "bob"),
            )
        } finally {
            file.deleteIfExists()
        }
    }

    test("stdin input uses provided stream instead of global system in") {
        val stdin = ByteArrayInputStream("""[{"id":1}]""".toByteArray())
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf("users" to FakeReader(header = listOf("id"), chunks = emptyList())),
        )
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { FakeWriter(mapOf("users" to FakeTableImportSession(targetColumns = listOf(targetColumns.first())))) },
        )

        importer.import(
            pool = pool,
            input = ImportInput.Stdin("users", stdin),
            format = DataExportFormat.JSON,
        )

        readerFactory.seenInputs.single() shouldBe stdin
    }

    test("directory input processes tables deterministically without explicit order") {
        val dir = Files.createTempDirectory("streaming-import-dir-")
        try {
            Files.writeString(dir.resolve("b.json"), "[]")
            Files.writeString(dir.resolve("a.json"), "[]")
            val readerFactory = FakeReaderFactory(
                readersByTable = mapOf(
                    "a" to FakeReader(header = listOf("id"), chunks = emptyList()),
                    "b" to FakeReader(header = listOf("id"), chunks = emptyList()),
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

            importer.import(
                pool = pool,
                input = ImportInput.Directory(dir),
                format = DataExportFormat.JSON,
            )

            readerFactory.createdTables shouldContainExactly listOf("a", "b")
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    test("directory input respects explicit table order") {
        val dir = Files.createTempDirectory("streaming-import-dir-")
        try {
            Files.writeString(dir.resolve("a.json"), "[]")
            Files.writeString(dir.resolve("b.json"), "[]")
            val readerFactory = FakeReaderFactory(
                readersByTable = mapOf(
                    "a" to FakeReader(header = listOf("id"), chunks = emptyList()),
                    "b" to FakeReader(header = listOf("id"), chunks = emptyList()),
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

            importer.import(
                pool = pool,
                input = ImportInput.Directory(dir, tableOrder = listOf("b", "a")),
                format = DataExportFormat.JSON,
            )

            readerFactory.createdTables shouldContainExactly listOf("b", "a")
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    test("directory input respects table filter") {
        val dir = Files.createTempDirectory("streaming-import-dir-")
        try {
            Files.writeString(dir.resolve("a.json"), "[]")
            Files.writeString(dir.resolve("b.json"), "[]")
            val readerFactory = FakeReaderFactory(
                readersByTable = mapOf(
                    "a" to FakeReader(header = listOf("id"), chunks = emptyList()),
                    "b" to FakeReader(header = listOf("id"), chunks = emptyList()),
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

            importer.import(
                pool = pool,
                input = ImportInput.Directory(dir, tableFilter = listOf("b")),
                format = DataExportFormat.JSON,
            )

            readerFactory.createdTables shouldContainExactly listOf("b")
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    test("directory input rejects duplicate table order entries") {
        val dir = Files.createTempDirectory("streaming-import-dir-")
        try {
            Files.writeString(dir.resolve("users.json"), "[]")
            val readerFactory = FakeReaderFactory(
                readersByTable = mapOf("users" to FakeReader(header = listOf("id"), chunks = emptyList())),
            )
            val importer = StreamingImporter(
                readerFactory = readerFactory,
                writerLookup = {
                    FakeWriter(
                        mapOf("users" to FakeTableImportSession(targetColumns = listOf(targetColumns.first()))),
                    )
                },
            )

            val ex = shouldThrow<IllegalArgumentException> {
                importer.import(
                    pool = pool,
                    input = ImportInput.Directory(dir, tableOrder = listOf("users", "users")),
                    format = DataExportFormat.JSON,
                )
            }

            ex.message shouldBe "ImportInput.Directory.tableOrder contains duplicate tables: users"
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    test("directory input rejects missing tables in filter") {
        val dir = Files.createTempDirectory("streaming-import-dir-")
        try {
            Files.writeString(dir.resolve("users.json"), "[]")
            val readerFactory = FakeReaderFactory(
                readersByTable = mapOf("users" to FakeReader(header = listOf("id"), chunks = emptyList())),
            )
            val importer = StreamingImporter(
                readerFactory = readerFactory,
                writerLookup = {
                    FakeWriter(
                        mapOf("users" to FakeTableImportSession(targetColumns = listOf(targetColumns.first()))),
                    )
                },
            )

            val ex = shouldThrow<IllegalArgumentException> {
                importer.import(
                    pool = pool,
                    input = ImportInput.Directory(dir, tableFilter = listOf("missing")),
                    format = DataExportFormat.JSON,
                )
            }

            ex.message shouldBe "ImportInput.Directory.tableFilter references tables without matching files: missing"
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    test("directory input rejects incomplete explicit table order") {
        val dir = Files.createTempDirectory("streaming-import-dir-")
        try {
            Files.writeString(dir.resolve("a.json"), "[]")
            Files.writeString(dir.resolve("b.json"), "[]")
            val readerFactory = FakeReaderFactory(
                readersByTable = mapOf(
                    "a" to FakeReader(header = listOf("id"), chunks = emptyList()),
                    "b" to FakeReader(header = listOf("id"), chunks = emptyList()),
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

            val ex = shouldThrow<IllegalArgumentException> {
                importer.import(
                    pool = pool,
                    input = ImportInput.Directory(dir, tableOrder = listOf("a")),
                    format = DataExportFormat.JSON,
                )
            }

            ex.message shouldBe "ImportInput.Directory.tableOrder must cover all candidate tables, missing order for: b"
        } finally {
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    test("imports rows positionally when reader has no header") {
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf(
                "users" to FakeReader(
                    header = null,
                    chunks = listOf(
                        chunk(
                            table = "users",
                            columns = emptyList(),
                            rows = listOf(arrayOf<Any?>(1L, "alice")),
                        )
                    )
                )
            )
        )
        val session = FakeTableImportSession(targetColumns = targetColumns)
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

            result.tables.single().rowsInserted shouldBe 1
            session.writtenChunks.single().columns.map { it.name } shouldContainExactly listOf("id", "name")
            session.writtenChunks.single().rows.single().toList() shouldContainExactly listOf(1L, "alice")
        } finally {
            file.deleteIfExists()
        }
    }

    test("preflight header mismatch aborts before first write regardless of onError") {
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf(
                "users" to FakeReader(
                    header = listOf("missing"),
                    chunks = listOf(
                        chunk(
                            table = "users",
                            columns = listOf("missing"),
                            rows = listOf(arrayOf<Any?>("x")),
                        )
                    )
                )
            )
        )
        val session = FakeTableImportSession(targetColumns = targetColumns)
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )
        val file = Files.createTempFile("streaming-import-", ".json")
        try {
            shouldThrow<ImportSchemaMismatchException> {
                importer.import(
                    pool = pool,
                    input = ImportInput.SingleFile("users", file),
                    format = DataExportFormat.JSON,
                    options = ImportOptions(onError = OnError.LOG),
                )
            }
            session.writtenChunks.shouldHaveSize(0)
        } finally {
            file.deleteIfExists()
        }
    }

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

    test("zero chunk path still produces table summary") {
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf("users" to FakeReader(header = listOf("id"), chunks = emptyList()))
        )
        val session = FakeTableImportSession(targetColumns = listOf(targetColumns.first()))
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
            result.tables.single().table shouldBe "users"
            result.tables.single().rowsInserted shouldBe 0
            result.tables.single().targetColumns shouldContainExactly listOf(ColumnDescriptor("id", false, "INTEGER"))
        } finally {
            file.deleteIfExists()
        }
    }
})

private object ImporterNoopConnectionPool : ConnectionPool {
    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE
    override fun borrow(): Connection = error("Fake importer tests do not borrow JDBC connections")
    override fun activeConnections(): Int = 0
    override fun close() = Unit
}

private class FakeReaderFactory(
    private val readersByTable: Map<String, FakeReader>,
) : DataChunkReaderFactory {
    val createdTables = mutableListOf<String>()
    val seenInputs = mutableListOf<InputStream>()

    override fun create(
        format: DataExportFormat,
        input: InputStream,
        table: String,
        chunkSize: Int,
        options: ImportOptions,
    ): DataChunkReader {
        createdTables += table
        seenInputs += input
        return readersByTable[table] ?: error("No fake reader configured for table '$table'")
    }
}

private class FakeReader(
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

private class FakeWriter(
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

private class FakeTableImportSession(
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

    override fun commitChunk() {
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
