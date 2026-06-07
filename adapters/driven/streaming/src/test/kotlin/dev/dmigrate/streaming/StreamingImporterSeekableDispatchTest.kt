package dev.dmigrate.streaming

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.format.data.ChunkColumnSchema
import dev.dmigrate.format.data.ChunkSchema
import dev.dmigrate.format.data.DataChunkReader
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.FormatReadOptions
import dev.dmigrate.format.data.SchemaOrigin
import dev.dmigrate.format.data.SeekableChunkSource
import dev.dmigrate.format.data.SeekableDataChunkReaderFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path
import java.sql.Types

/**
 * S7c: Fake-basierte Tests fuer den Seekable-Dispatch im
 * StreamingImporter → TableImporter. Bleibt parquet-frei
 * (`:adapters:driven:streaming` hat keine `formats-parquet`-
 * Dependency); echter `ParquetSeekableDataChunkReaderFactory`-
 * Bezug lebt im CLI-Wiring und im E2E-Test (S7d/e).
 */
/**
 * Fake-Factory, die genau die SeekableDataChunkReaderFactory-API
 * mitschneidet. Liefert vorab konfigurierte Reader pro Tabelle.
 * Top-Level, weil Kotest FunSpec-Init-Bloecke keine
 * Klassen-Definitionen erlauben.
 */
private class RecordingSeekableFactory(
    private val readersByTable: Map<String, FakeReader>,
) : SeekableDataChunkReaderFactory {
    data class Call(
        val format: DataExportFormat,
        val source: SeekableChunkSource,
        val table: String,
        val schema: ChunkSchema,
        val chunkSize: Int,
    )

    val calls = mutableListOf<Call>()

    override fun create(
        format: DataExportFormat,
        source: SeekableChunkSource,
        table: String,
        schema: ChunkSchema,
        chunkSize: Int,
        options: FormatReadOptions,
    ): DataChunkReader {
        calls += Call(format, source, table, schema, chunkSize)
        return readersByTable[table]
            ?: error("RecordingSeekableFactory has no reader for table '$table'")
    }
}

class StreamingImporterSeekableDispatchTest : FunSpec({

    val pool = ImporterNoopConnectionPool
    val targetColumns = listOf(
        TargetColumn("id", nullable = false, jdbcType = Types.INTEGER, sqlTypeName = "INTEGER"),
    )

    fun chunk(table: String, rows: List<Array<Any?>>, chunkIndex: Long): DataChunk = DataChunk(
        table = table,
        columns = listOf(ColumnDescriptor("id", nullable = false)),
        rows = rows,
        chunkIndex = chunkIndex,
    )

    val schema = ChunkSchema(
        table = "users",
        origin = SchemaOrigin.JDBC_METADATA,
        columns = listOf(ChunkColumnSchema("id", false, dev.dmigrate.core.model.NeutralType.BigInteger)),
    )

    test("Seekable Single-File: dispatch ruft die seekableReaderFactory und NICHT die Stream-Factory") {
        val seekableFactory = RecordingSeekableFactory(
            readersByTable = mapOf("users" to FakeReader(header = null, chunks = listOf(
                chunk("users", listOf(arrayOf<Any?>(1L)), chunkIndex = 0L),
            ))),
        )
        val streamFactory = FakeReaderFactory(readersByTable = emptyMap())
        val session = FakeTableImportSession(targetColumns = targetColumns)
        val importer = StreamingImporter(
            readerFactory = streamFactory,
            seekableReaderFactory = seekableFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )

        val result = importer.import(
            pool = pool,
            input = ImportInput.ResolvedSingleFile(
                table = "users",
                path = Path.of("/tmp/x.parquet"),
                schema = schema,
                contentSha256 = null,
                manifestPresent = true,
            ),
            format = DataExportFormat.PARQUET,
        )

        result.tables.single().table shouldBe "users"
        seekableFactory.calls.size shouldBe 1
        val call = seekableFactory.calls.single()
        call.table shouldBe "users"
        call.format shouldBe DataExportFormat.PARQUET
        call.source shouldBe SeekableChunkSource.Local(Path.of("/tmp/x.parquet"))
        call.schema shouldBe schema
        streamFactory.createdTables.shouldBeEmpty()
    }

    test("Seekable Bundle: dispatched pro Tabelle einmal, deterministisch in Manifest-Reihenfolge") {
        val seekableFactory = RecordingSeekableFactory(
            readersByTable = mapOf(
                "users" to FakeReader(header = null, chunks = emptyList()),
                "orders" to FakeReader(header = null, chunks = emptyList()),
            ),
        )
        val streamFactory = FakeReaderFactory(readersByTable = emptyMap())
        val importer = StreamingImporter(
            readerFactory = streamFactory,
            seekableReaderFactory = seekableFactory,
            writerLookup = {
                FakeWriter(
                    mapOf(
                        "users" to FakeTableImportSession(targetColumns = targetColumns),
                        "orders" to FakeTableImportSession(targetColumns = targetColumns),
                    )
                )
            },
        )

        val bundle = ImportInput.ResolvedBundle(
            bundleRoot = Path.of("/tmp/bundle"),
            tables = listOf(
                ResolvedBundleTableBinding(
                    table = "users",
                    path = Path.of("/tmp/bundle/users.parquet"),
                    schema = schema,
                ),
                ResolvedBundleTableBinding(
                    table = "orders",
                    path = Path.of("/tmp/bundle/orders.parquet"),
                    schema = schema.copy(table = "orders"),
                ),
            ),
            resumeFingerprint = BundleResumeFingerprint(
                manifestSha256 = "deadbeef",
                formatVersion = "1.0",
                producerVersion = "test",
                tableOrder = listOf("users", "orders"),
            ),
        )

        importer.import(pool = pool, input = bundle, format = DataExportFormat.PARQUET)

        seekableFactory.calls.map { it.table } shouldContainExactly listOf("users", "orders")
        streamFactory.createdTables.shouldBeEmpty()
    }

    test("Seekable + null-Factory: Pre-Stream-Check faengt den Wiring-Bug mit klarer Meldung ab") {
        val streamFactory = FakeReaderFactory(readersByTable = emptyMap())
        val importer = StreamingImporter(
            readerFactory = streamFactory,
            seekableReaderFactory = null,  // bewusst null — Wiring-Drift simulieren
            writerLookup = { FakeWriter(mapOf("users" to FakeTableImportSession(targetColumns = targetColumns))) },
        )

        val ex = shouldThrow<IllegalStateException> {
            importer.import(
                pool = pool,
                input = ImportInput.ResolvedSingleFile(
                    table = "users",
                    path = Path.of("/tmp/x.parquet"),
                    schema = schema,
                    contentSha256 = null,
                    manifestPresent = true,
                ),
                format = DataExportFormat.PARQUET,
            )
        }
        ex.message!! shouldContain "Seekable input requires seekableReaderFactory"
        ex.message!! shouldContain "table 'users'"
        streamFactory.createdTables.shouldBeEmpty()
    }

    /**
     * Resume-Skip-Smoke (Plan-Review-v4 Finding 5): mit
     * `resumeState.committedChunks = N` muessen vor dem ersten
     * `commitChunk(...)` mindestens N `nextChunk()`-Aufrufe passieren
     * (TableImporter.skipCommittedChunks-Loop), und der erste
     * committete Chunk traegt `chunkIndex = N`. Damit ist der
     * S3-Reader-Skip-Pfad ueber den neuen Seekable-Dispatch
     * nachweislich aktiv. Voller Resume-E2E lebt in S9b.
     */
    test("Seekable Resume-Smoke: skipCommittedChunks-Loop ueber Fake-nextChunk()-Counts") {
        val nextChunkCalls = java.util.concurrent.atomic.AtomicInteger(0)
        val reader = object : FakeReader(
            header = null,
            chunks = listOf(
                chunk("users", listOf(arrayOf<Any?>(1L)), chunkIndex = 0L),
                chunk("users", listOf(arrayOf<Any?>(2L)), chunkIndex = 1L),
                chunk("users", listOf(arrayOf<Any?>(3L)), chunkIndex = 2L),
                chunk("users", listOf(arrayOf<Any?>(4L)), chunkIndex = 3L),
            ),
        ) {
            override fun consumeNextChunk(): DataChunk? {
                nextChunkCalls.incrementAndGet()
                return super.consumeNextChunk()
            }
        }
        val seekableFactory = RecordingSeekableFactory(readersByTable = mapOf("users" to reader))
        val session = FakeTableImportSession(targetColumns = targetColumns)
        val importer = StreamingImporter(
            readerFactory = FakeReaderFactory(readersByTable = emptyMap()),
            seekableReaderFactory = seekableFactory,
            writerLookup = { FakeWriter(mapOf("users" to session)) },
        )

        importer.import(
            pool = pool,
            input = ImportInput.ResolvedSingleFile(
                table = "users",
                path = Path.of("/tmp/x.parquet"),
                schema = schema,
                contentSha256 = null,
                manifestPresent = true,
            ),
            format = DataExportFormat.PARQUET,
            resumeStateByTable = mapOf("users" to ImportTableResumeState(committedChunks = 2L)),
        )

        // Mindestens 2 nextChunk()-Aufrufe sind der Skip-Loop;
        // der erste committete Chunk hat chunkIndex = 2.
        val totalCalls = nextChunkCalls.get()
        require(totalCalls >= 2) { "expected >=2 nextChunk() calls (skip), got $totalCalls" }
        session.writtenChunks.first().chunkIndex shouldBe 2L
    }
})
