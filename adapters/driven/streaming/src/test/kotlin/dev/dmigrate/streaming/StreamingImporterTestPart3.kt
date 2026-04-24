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

class StreamingImporterTestPart3 : FunSpec({

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

    // ─── Progress Event Tests (§8.2) ────────────────────────────────

    // §8.2 #1
    test("progress: emits run started for resolved inputs") {
        val readerFactory = FakeReaderFactory(mapOf(
            "users" to FakeReader(header = listOf("id"), chunks = listOf(
                chunk("users", listOf("id"), listOf(arrayOf<Any?>(1L)))
            ))
        ))
        val session = FakeTableImportSession(targetColumns = listOf(
            TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER")))
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }
        val file = Files.createTempFile("prog-", ".json")
        try {
            StreamingImporter(readerFactory, { FakeWriter(mapOf("users" to session)) })
                .import(pool = pool, input = ImportInput.SingleFile("users", file),
                    format = DataExportFormat.JSON, progressReporter = reporter)

            val runStarted = events.filterIsInstance<ProgressEvent.RunStarted>()
            runStarted.size shouldBe 1
            runStarted[0].operation shouldBe ProgressOperation.IMPORT
            runStarted[0].totalTables shouldBe 1
        } finally { file.deleteIfExists() }
    }

    // §8.2 #2
    test("progress: emits started chunk progress and finished for successful table") {
        val readerFactory = FakeReaderFactory(mapOf(
            "users" to FakeReader(header = listOf("id", "name"), chunks = listOf(
                chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(1L, "a")))
            ))
        ))
        val session = FakeTableImportSession(targetColumns = targetColumns)
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }
        val file = Files.createTempFile("prog-", ".json")
        try {
            StreamingImporter(readerFactory, { FakeWriter(mapOf("users" to session)) })
                .import(pool = pool, input = ImportInput.SingleFile("users", file),
                    format = DataExportFormat.JSON, progressReporter = reporter)

            val types = events.map { it::class.simpleName }
            types shouldContainExactly listOf("RunStarted", "ImportTableStarted", "ImportChunkProcessed", "ImportTableFinished")

            val finished = events.filterIsInstance<ProgressEvent.ImportTableFinished>().single()
            finished.status shouldBe TableProgressStatus.COMPLETED
            finished.rowsInserted shouldBe 1
        } finally { file.deleteIfExists() }
    }

    // §8.2 #3
    test("progress: emits chunk progress only after commit") {
        val readerFactory = FakeReaderFactory(mapOf(
            "users" to FakeReader(header = listOf("id", "name"), chunks = listOf(
                chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(1L, "a")))
            ))
        ))
        val eventLog = mutableListOf<String>()
        val session = object : FakeTableImportSession(targetColumns = targetColumns) {
            override fun commitChunk() {
                eventLog += "commit"
                super.commitChunk()
            }
        }
        val reporter = ProgressReporter { eventLog += "event:${it::class.simpleName}" }
        val file = Files.createTempFile("prog-", ".json")
        try {
            StreamingImporter(readerFactory, { FakeWriter(mapOf("users" to session)) })
                .import(pool = pool, input = ImportInput.SingleFile("users", file),
                    format = DataExportFormat.JSON, progressReporter = reporter)

            val commitIdx = eventLog.indexOf("commit")
            val chunkEventIdx = eventLog.indexOf("event:ImportChunkProcessed")
            commitIdx shouldBe (chunkEventIdx - 1)
        } finally { file.deleteIfExists() }
    }

    // §8.2 #6: table error with --on-error skip returns summary with error, emits FAILED
    test("progress: returned table error emits finished event with failed status") {
        val readerFactory = FakeReaderFactory(mapOf(
            "users" to FakeReader(header = listOf("id", "name"), chunks = listOf(
                chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(1L, "a")))
            ))
        ))
        val session = object : FakeTableImportSession(targetColumns = targetColumns) {
            override fun commitChunk() {
                throw RuntimeException("commit failed")
            }
        }
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }
        val file = Files.createTempFile("prog-", ".json")
        try {
            // Use --on-error skip so commit failure doesn't abort, allowing TableFinished emission
            val result = StreamingImporter(readerFactory, { FakeWriter(mapOf("users" to session)) })
                .import(pool = pool, input = ImportInput.SingleFile("users", file),
                    format = DataExportFormat.JSON,
                    options = ImportOptions(onError = OnError.SKIP),
                    progressReporter = reporter)

            result.tables.single().error shouldNotBe null
            val finished = events.filterIsInstance<ProgressEvent.ImportTableFinished>()
            finished shouldHaveSize 1
            finished[0].status shouldBe TableProgressStatus.FAILED
        } finally { file.deleteIfExists() }
    }

    // §8.2 #4: continued chunk local failure increments rowsFailed in progress events
    test("progress: continued chunk local failure increments rowsFailed") {
        // First chunk succeeds, second chunk has wrong column count → normalization failure
        val readerFactory = FakeReaderFactory(mapOf(
            "users" to FakeReader(header = listOf("id", "name"), chunks = listOf(
                chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(1L, "a"))),
                chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(2L, "b")), chunkIndex = 1),
            ), nextChunkFailures = mapOf(1 to RuntimeException("read error on chunk 2")))
        ))
        val session = FakeTableImportSession(targetColumns = targetColumns)
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }
        val file = Files.createTempFile("prog-", ".json")
        try {
            StreamingImporter(readerFactory, { FakeWriter(mapOf("users" to session)) })
                .import(pool = pool, input = ImportInput.SingleFile("users", file),
                    format = DataExportFormat.JSON,
                    options = ImportOptions(onError = OnError.SKIP),
                    progressReporter = reporter)

            // First chunk → successful ChunkProcessed; second read fails → no extra ChunkProcessed
            val chunks = events.filterIsInstance<ProgressEvent.ImportChunkProcessed>()
            chunks.size shouldBe 1
            chunks[0].rowsInserted shouldBe 1
        } finally { file.deleteIfExists() }
    }

    // §8.2 #5: reader failure may end table without additional chunk progress event
    test("progress: reader failure does not emit synthetic chunk event") {
        val readerFactory = FakeReaderFactory(mapOf(
            "users" to FakeReader(header = listOf("id", "name"), chunks = listOf(
                chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(1L, "a"))),
            ), nextChunkFailures = mapOf(1 to RuntimeException("corrupt data")))
        ))
        val session = FakeTableImportSession(targetColumns = targetColumns)
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }
        val file = Files.createTempFile("prog-", ".json")
        try {
            StreamingImporter(readerFactory, { FakeWriter(mapOf("users" to session)) })
                .import(pool = pool, input = ImportInput.SingleFile("users", file),
                    format = DataExportFormat.JSON,
                    options = ImportOptions(onError = OnError.SKIP),
                    progressReporter = reporter)

            // Only 1 ChunkProcessed for the successful first chunk; reader failure on second read
            // does NOT produce an additional ChunkProcessed event
            val chunks = events.filterIsInstance<ProgressEvent.ImportChunkProcessed>()
            chunks.size shouldBe 1

            val finished = events.filterIsInstance<ProgressEvent.ImportTableFinished>().single()
            finished.status shouldBe TableProgressStatus.FAILED
        } finally { file.deleteIfExists() }
    }

    // §8.2 #7: failedFinish emits finished event with FAILED status
    test("progress: failedFinish emits finished event with failed status") {
        val readerFactory = FakeReaderFactory(mapOf(
            "users" to FakeReader(header = listOf("id", "name"), chunks = listOf(
                chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(1L, "a")))
            ))
        ))
        val session = FakeTableImportSession(
            targetColumns = targetColumns,
            finishResult = FinishTableResult.PartialFailure(
                adjustments = emptyList(),
                cause = RuntimeException("reseed failed"),
            ),
        )
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }
        val file = Files.createTempFile("prog-", ".json")
        try {
            StreamingImporter(readerFactory, { FakeWriter(mapOf("users" to session)) })
                .import(pool = pool, input = ImportInput.SingleFile("users", file),
                    format = DataExportFormat.JSON, progressReporter = reporter)

            val finished = events.filterIsInstance<ProgressEvent.ImportTableFinished>().single()
            finished.status shouldBe TableProgressStatus.FAILED
            finished.rowsInserted shouldBe 1
        } finally { file.deleteIfExists() }
    }

    // §8.2 #8: fatal abort (default --on-error abort) propagates without synthetic finished event
    test("progress: fatal abort propagates without synthetic finished event") {
        val readerFactory = FakeReaderFactory(mapOf(
            "users" to FakeReader(header = listOf("id", "name"), chunks = listOf(
                chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(1L, "a")))
            ))
        ))
        val session = object : FakeTableImportSession(targetColumns = targetColumns) {
            override fun commitChunk() {
                throw RuntimeException("fatal")
            }
        }
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }
        val file = Files.createTempFile("prog-", ".json")
        try {
            shouldThrow<RuntimeException> {
                StreamingImporter(readerFactory, { FakeWriter(mapOf("users" to session)) })
                    .import(pool = pool, input = ImportInput.SingleFile("users", file),
                        format = DataExportFormat.JSON, progressReporter = reporter)
            }

            // Fatal throws bypass TableFinished (§4.10)
            val finished = events.filterIsInstance<ProgressEvent.ImportTableFinished>()
            finished shouldHaveSize 0
        } finally { file.deleteIfExists() }
    }

    test("E3: truncate guard — resumed table with committed chunks does not re-truncate") {
        val readerFactory = FakeReaderFactory(
            readersByTable = mapOf(
                "users" to FakeReader(
                    header = listOf("id", "name"),
                    chunks = listOf(
                        // Chunk 0+1 already committed in previous run → skipped
                        chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(1L, "a")), chunkIndex = 0),
                        chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(2L, "b")), chunkIndex = 1),
                        // Chunk 2 is the new one that should be written
                        chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(3L, "c")), chunkIndex = 2),
                    )
                )
            )
        )
        val session = FakeTableImportSession(targetColumns = targetColumns)
        var capturedOptions: ImportOptions? = null
        val writer = object : DataWriter {
            override val dialect = DatabaseDialect.SQLITE
            override fun schemaSync() = error("not used")
            override fun openTable(
                pool: ConnectionPool,
                table: String,
                options: ImportOptions,
            ): TableImportSession {
                capturedOptions = options
                return session
            }
        }
        val importer = StreamingImporter(
            readerFactory = readerFactory,
            writerLookup = { writer },
        )
        val file = Files.createTempFile("streaming-import-truncate-guard-", ".json")
        try {
            val result = importer.import(
                pool = pool,
                input = ImportInput.SingleFile("users", file),
                format = DataExportFormat.JSON,
                options = ImportOptions(truncate = true),
                resumeStateByTable = mapOf("users" to ImportTableResumeState(committedChunks = 2)),
            )
            result.success shouldBe true
            // Truncate guard: openTable must have received truncate=false
            capturedOptions shouldNotBe null
            capturedOptions!!.truncate shouldBe false
            // Only chunk 2 should have been written (chunks 0+1 skipped)
            session.writtenChunks shouldHaveSize 1
        } finally {
            file.deleteIfExists()
        }
    }
})

