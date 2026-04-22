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

})
