package dev.dmigrate.streaming

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.DataExportFormat
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.sql.Connection
import java.sql.Types

class TableImporterTest : FunSpec({

    val targetColumns = listOf(
        TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
        TargetColumn("name", nullable = true, jdbcType = Types.VARCHAR, sqlTypeName = "VARCHAR"),
    )

    fun chunk(
        table: String,
        columns: List<String>,
        rows: List<Array<Any?>>,
        chunkIndex: Long,
    ) = DataChunk(
        table = table,
        columns = columns.map { ColumnDescriptor(it, nullable = true) },
        rows = rows,
        chunkIndex = chunkIndex,
    )

    test("resume skips committed chunks and disables truncate before opening the writer session") {
        val reader = FakeReader(
            header = listOf("name", "id"),
            chunks = listOf(
                chunk("users", listOf("name", "id"), listOf(arrayOf<Any?>("ignored", 1L)), chunkIndex = 0),
                chunk("users", listOf("name", "id"), listOf(arrayOf<Any?>("alice", 2L)), chunkIndex = 1),
            ),
        )
        val readerFactory = FakeReaderFactory(mapOf("users" to reader))
        val session = FakeTableImportSession(targetColumns = targetColumns)
        val writer = RecordingWriter(session)
        val commits = mutableListOf<ImportChunkCommit>()
        var openedColumns: List<TargetColumn>? = null

        val importer = TableImporter(readerFactory) { _, columns -> openedColumns = columns }
        val summary = importer.import(
            TableImportParams(
                pool = ImporterNoopConnectionPool,
                writer = writer,
                tableInput = ResolvedTableInput("users") { ByteArrayInputStream("[]".toByteArray()) },
                format = DataExportFormat.JSON,
                options = ImportOptions(truncate = true),
                config = PipelineConfig(chunkSize = 100),
                reporter = NoOpProgressReporter,
                ordinal = 1,
                tableCount = 1,
                resumeState = ImportTableResumeState(committedChunks = 1),
                onChunkCommitted = commits::add,
            )
        )

        writer.openedOptions.single().truncate shouldBe false
        openedColumns shouldBe targetColumns
        session.writtenChunks.shouldHaveSize(1)
        session.writtenChunks.single().chunkIndex shouldBe 1L
        session.writtenChunks.single().columns.map { it.name } shouldContainExactly listOf("id", "name")
        session.writtenChunks.single().rows.map { it.toList() } shouldContainExactly listOf(listOf(2L, "alice"))
        commits.single().chunkIndex shouldBe 1L
        commits.single().chunksCommitted shouldBe 2L
        summary.rowsInserted shouldBe 1L
        summary.rowsFailed shouldBe 0L
    }

    test("partial finish is preserved in the summary including a suppressed close cause") {
        val adjustment = SequenceAdjustment("users", "id", null, 42L)
        val finishCause = IllegalStateException("trigger re-enable failed").apply {
            addSuppressed(IllegalArgumentException("close cleanup failed"))
        }
        val readerFactory = FakeReaderFactory(
            mapOf(
                "users" to FakeReader(
                    header = listOf("id", "name"),
                    chunks = listOf(
                        chunk("users", listOf("id", "name"), listOf(arrayOf<Any?>(7L, "bob")), chunkIndex = 0),
                    ),
                )
            )
        )
        val session = FakeTableImportSession(
            targetColumns = targetColumns,
            finishResult = FinishTableResult.PartialFailure(listOf(adjustment), finishCause),
        )
        val writer = RecordingWriter(session)
        val importer = TableImporter(readerFactory) { _, _ -> }

        val summary = importer.import(
            TableImportParams(
                pool = ImporterNoopConnectionPool,
                writer = writer,
                tableInput = ResolvedTableInput("users") { ByteArrayInputStream("[]".toByteArray()) },
                format = DataExportFormat.JSON,
                options = ImportOptions(),
                config = PipelineConfig(chunkSize = 100),
                reporter = NoOpProgressReporter,
                ordinal = 1,
                tableCount = 1,
                resumeState = null,
                onChunkCommitted = {},
            )
        )

        summary.sequenceAdjustments shouldContainExactly listOf(adjustment)
        summary.failedFinish!!.causeMessage shouldBe "trigger re-enable failed"
        summary.failedFinish!!.causeClass shouldBe IllegalStateException::class.qualifiedName
        summary.failedFinish!!.closeCauseMessage shouldBe "close cleanup failed"
        summary.failedFinish!!.closeCauseClass shouldBe IllegalArgumentException::class.qualifiedName
        summary.error shouldBe null
    }
})

private class RecordingWriter(
    private val session: TableImportSession,
) : DataWriter {
    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE
    val openedOptions = mutableListOf<ImportOptions>()

    override fun schemaSync(): SchemaSync = object : SchemaSync {
        override fun reseedGenerators(
            conn: Connection,
            table: String,
            importedColumns: List<ColumnDescriptor>,
        ): List<SequenceAdjustment> = emptyList()
    }

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession {
        openedOptions += options
        return session
    }
}

