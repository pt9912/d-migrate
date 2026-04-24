package dev.dmigrate.streaming

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.ChunkSequence
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.io.OutputStream
import java.nio.file.Files
import java.sql.Connection
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readText

/**
 * Tests für [StreamingExporter] mit Test-Doubles für DataReader, TableLister
 * und DataChunkWriter — keine echte JDBC- oder Format-Library nötig.
 *
 * Verifiziert:
 * - Single-Table-Export → Stdout / SingleFile
 * - Multi-Table-Export → FilePerTable
 * - §6.17 Empty-Table-Vertrag (begin/end werden auch bei 0 Rows aufgerufen)
 * - DataFilter wird durchgereicht
 * - ExportResult-Statistiken (rows, chunks, bytes, durationMs)
 * - Error-Handling pro Tabelle (eine fehlschlagende Tabelle führt nicht zum
 *   Abbruch der gesamten Operation)
 * - Auto-Listing wenn `tables=emptyList()`
 */
class StreamingExporterTest : FunSpec({

    val cols = listOf(
        ColumnDescriptor("id", nullable = false, sqlTypeName = "INTEGER"),
        ColumnDescriptor("name", nullable = false, sqlTypeName = "VARCHAR"),
    )

    fun chunk(table: String, idx: Long, vararg rows: Array<Any?>) =
        DataChunk(table = table, columns = cols, rows = rows.toList(), chunkIndex = idx)

    fun emptyChunk(table: String) =
        DataChunk(table = table, columns = cols, rows = emptyList(), chunkIndex = 0)

    val pool = NoopConnectionPool

    // ─── 0.9.0 Phase C.2: Mid-Table-Resume-Pfad ─────────────────
    // (`docs/ImpPlan-0.9.0-C2.md` §5.2)
    // ────────────────────────────────────────────────────────────

    test("C.2: resumeMarkers triggers the 5-param streamTable overload") {
        val reader = FakeDataReader(
            mapOf("users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "alice"))))
        )
        val marker = dev.dmigrate.driver.data.ResumeMarker(
            markerColumn = "id",
            tieBreakerColumns = listOf("name"),
            position = null,
        )
        val tmpDir = Files.createTempDirectory("d-migrate-c2-exp-")
        val out = ExportOutput.FilePerTable(tmpDir)
        StreamingExporter(reader, FakeTableLister(listOf("users")), RecordingChunkWriterFactory())
            .export(
                pool = pool,
                tables = listOf("users"),
                output = out,
                format = DataExportFormat.JSON,
                resumeMarkers = mapOf("users" to marker),
            )
        reader.lastResumeMarkers["users"] shouldBe marker
    }

    test("C.2: onChunkProcessed emits position with last-row marker/tie-breaker values") {
        val reader = FakeDataReader(
            mapOf("users" to listOf(
                chunk(
                    "users", 0,
                    arrayOf<Any?>(1, "alice"),
                    arrayOf<Any?>(2, "bob"),
                ),
                chunk(
                    "users", 1,
                    arrayOf<Any?>(3, "carol"),
                ),
            ))
        )
        val marker = dev.dmigrate.driver.data.ResumeMarker(
            markerColumn = "id",
            tieBreakerColumns = listOf("name"),
            position = null,
        )
        val progressCalls = mutableListOf<TableChunkProgress>()
        val tmpDir = Files.createTempDirectory("d-migrate-c2-chunkcb-")
        StreamingExporter(reader, FakeTableLister(listOf("users")), RecordingChunkWriterFactory())
            .export(
                pool = pool,
                tables = listOf("users"),
                output = ExportOutput.FilePerTable(tmpDir),
                format = DataExportFormat.JSON,
                resumeMarkers = mapOf("users" to marker),
                onChunkProcessed = { progressCalls += it },
            )
        progressCalls.size shouldBe 2
        progressCalls[0].position.lastMarkerValue shouldBe 2
        progressCalls[0].position.lastTieBreakerValues shouldContainExactly listOf<Any?>("bob")
        progressCalls[1].position.lastMarkerValue shouldBe 3
        progressCalls[1].position.lastTieBreakerValues shouldContainExactly listOf<Any?>("carol")
    }

    test("C.2: onChunkProcessed is NOT invoked for empty chunks") {
        val reader = FakeDataReader(
            mapOf("users" to listOf(emptyChunk("users")))
        )
        val marker = dev.dmigrate.driver.data.ResumeMarker(
            markerColumn = "id",
            tieBreakerColumns = emptyList(),
            position = null,
        )
        val progressCalls = mutableListOf<TableChunkProgress>()
        val tmpDir = Files.createTempDirectory("d-migrate-c2-empty-")
        StreamingExporter(reader, FakeTableLister(listOf("users")), RecordingChunkWriterFactory())
            .export(
                pool = pool,
                tables = listOf("users"),
                output = ExportOutput.FilePerTable(tmpDir),
                format = DataExportFormat.JSON,
                resumeMarkers = mapOf("users" to marker),
                onChunkProcessed = { progressCalls += it },
        )
        progressCalls.shouldBeEmpty()
    }

    test("C.2: failing onChunkProcessed emits one warning and export continues") {
        val reader = FakeDataReader(
            mapOf("users" to listOf(
                chunk("users", 0, arrayOf<Any?>(1, "alice"), arrayOf<Any?>(2, "bob")),
                chunk("users", 1, arrayOf<Any?>(3, "carol")),
            ))
        )
        val marker = dev.dmigrate.driver.data.ResumeMarker(
            markerColumn = "id",
            tieBreakerColumns = emptyList(),
            position = null,
        )
        val warnings = mutableListOf<String>()
        val tmpDir = Files.createTempDirectory("d-migrate-c2-warning-")
        try {
            val result = StreamingExporter(reader, FakeTableLister(listOf("users")), RecordingChunkWriterFactory())
                .export(
                    pool = pool,
                    tables = listOf("users"),
                    output = ExportOutput.FilePerTable(tmpDir),
                    format = DataExportFormat.JSON,
                    resumeMarkers = mapOf("users" to marker),
                    onChunkProcessed = { throw IllegalStateException("checkpoint writer unavailable") },
                    warningSink = { warnings += it },
                )

            result.totalRows shouldBe 3
            result.totalChunks shouldBe 2
            warnings shouldContainExactly listOf(
                "Warning: Failed to persist chunk progress for table 'users': checkpoint writer unavailable"
            )
        } finally {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    test("C.2: tables without resumeMarker take the legacy 4-param path") {
        val reader = FakeDataReader(
            mapOf(
                "users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "alice"))),
                "orders" to listOf(chunk("orders", 0, arrayOf<Any?>(1, "o"))),
            )
        )
        val marker = dev.dmigrate.driver.data.ResumeMarker(
            markerColumn = "id",
            tieBreakerColumns = emptyList(),
            position = null,
        )
        val tmpDir = Files.createTempDirectory("d-migrate-c2-mixed-")
        StreamingExporter(reader, FakeTableLister(listOf("users", "orders")), RecordingChunkWriterFactory())
            .export(
                pool = pool,
                tables = listOf("users", "orders"),
                output = ExportOutput.FilePerTable(tmpDir),
                format = DataExportFormat.JSON,
                // only 'users' has a marker
                resumeMarkers = mapOf("users" to marker),
            )
        reader.lastResumeMarkers["users"] shouldBe marker
        reader.lastResumeMarkers["orders"] shouldBe null
    }

    // ─── Stdout single-table ─────────────────────────────────────

    test("Stdout output writes one table") {
        val reader = FakeDataReader(
            mapOf("users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "alice"), arrayOf<Any?>(2, "bob"))))
        )
        val factory = RecordingChunkWriterFactory()
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), factory)

        val result = exporter.export(
            pool = pool,
            tables = listOf("users"),
            output = ExportOutput.Stdout,
            format = DataExportFormat.JSON,
        )

        result.tables shouldContainExactly listOf(
            TableExportSummary(
                table = "users",
                rows = 2,
                chunks = 1,
                bytes = result.tables.single().bytes,
                durationMs = result.tables.single().durationMs,
            ),
        )
        result.totalRows shouldBe 2
        result.totalChunks shouldBe 1
        factory.events shouldContainExactly listOf("create:users", "begin:users", "write:users:2", "end:users", "close:users")
    }

    // ─── SingleFile single-table ─────────────────────────────────

    test("SingleFile output writes one table to a file") {
        val tmp = Files.createTempFile("streaming-test-", ".json")
        try {
            val reader = FakeDataReader(
                mapOf("orders" to listOf(chunk("orders", 0, arrayOf<Any?>(10, "first"))))
            )
            val factory = RecordingChunkWriterFactory()
            val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), factory)

            val result = exporter.export(
                pool = pool,
                tables = listOf("orders"),
                output = ExportOutput.SingleFile(tmp),
                format = DataExportFormat.JSON,
            )

            result.totalRows shouldBe 1
            result.totalChunks shouldBe 1
            // Datei wurde geschrieben (RecordingChunkWriter schreibt nur "<table>:<row>" als Marker)
            tmp.readText() shouldNotBe ""
        } finally {
            tmp.deleteIfExists()
        }
    }

    // ─── FilePerTable multi-table ────────────────────────────────

    test("FilePerTable output writes each table to its own file") {
        val tmpDir = Files.createTempDirectory("streaming-test-")
        try {
            val reader = FakeDataReader(
                mapOf(
                    "users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "alice"))),
                    "orders" to listOf(chunk("orders", 0, arrayOf<Any?>(10, "first"), arrayOf<Any?>(11, "second"))),
                )
            )
            val factory = RecordingChunkWriterFactory()
            val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), factory)

            val result = exporter.export(
                pool = pool,
                tables = listOf("users", "orders"),
                output = ExportOutput.FilePerTable(tmpDir),
                format = DataExportFormat.CSV,
            )

            result.tables.map { it.table } shouldContainExactly listOf("users", "orders")
            result.totalRows shouldBe 3
            Files.exists(tmpDir.resolve("users.csv")) shouldBe true
            Files.exists(tmpDir.resolve("orders.csv")) shouldBe true
        } finally {
            // Best-effort cleanup
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    test("FilePerTable preserves schema-qualified table names in file names (§6.9)") {
        val tmpDir = Files.createTempDirectory("streaming-test-")
        try {
            val reader = FakeDataReader(
                mapOf(
                    "public.orders" to listOf(chunk("public.orders", 0, arrayOf<Any?>(1, "a"))),
                    "reporting.orders" to listOf(chunk("reporting.orders", 0, arrayOf<Any?>(2, "b"))),
                )
            )
            val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())

            exporter.export(
                pool = pool,
                tables = listOf("public.orders", "reporting.orders"),
                output = ExportOutput.FilePerTable(tmpDir),
                format = DataExportFormat.JSON,
            )

            // Schema-qualifizierte Form bleibt im Dateinamen erhalten — keine Kollision
            Files.exists(tmpDir.resolve("public.orders.json")) shouldBe true
            Files.exists(tmpDir.resolve("reporting.orders.json")) shouldBe true
        } finally {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    // ─── §6.17: empty table contract ─────────────────────────────

    test("§6.17: empty table → begin and end are still called, write gets the empty chunk") {
        val reader = FakeDataReader(mapOf("empty_table" to listOf(emptyChunk("empty_table"))))
        val factory = RecordingChunkWriterFactory()
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), factory)

        val result = exporter.export(
            pool = pool,
            tables = listOf("empty_table"),
            output = ExportOutput.Stdout,
            format = DataExportFormat.JSON,
        )

        result.totalRows shouldBe 0
        result.totalChunks shouldBe 1
        // begin → write (mit 0 rows) → end → close
        factory.events shouldContainExactly listOf(
            "create:empty_table",
            "begin:empty_table",
            "write:empty_table:0",
            "end:empty_table",
            "close:empty_table",
        )
    }

    test("Reader that returns zero chunks reports an error") {
        val reader = FakeDataReader(mapOf("broken" to emptyList()))
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())

        val result = exporter.export(
            pool = pool,
            tables = listOf("broken"),
            output = ExportOutput.Stdout,
            format = DataExportFormat.JSON,
        )

        result.tables.single().error!!.contains("empty tables must still emit one chunk") shouldBe true
        result.success shouldBe false
    }

    // ─── DataFilter pass-through ────────────────────────────────

    test("DataFilter is passed through to the reader") {
        val filter = DataFilter.ParameterizedClause("id > ?", listOf(0))
        val reader = FakeDataReader(
            mapOf("users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "alice"))))
        )
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())

        exporter.export(
            pool = pool,
            tables = listOf("users"),
            output = ExportOutput.Stdout,
            format = DataExportFormat.JSON,
            filter = filter,
        )

        reader.lastFilter shouldBe filter
    }

    // ─── Auto-listing ────────────────────────────────────────────

    test("Empty tables list triggers TableLister.listTables") {
        val tmpDir = Files.createTempDirectory("streaming-test-")
        try {
            val reader = FakeDataReader(
                mapOf(
                    "a" to listOf(chunk("a", 0, arrayOf<Any?>(1, "x"))),
                    "b" to listOf(chunk("b", 0, arrayOf<Any?>(2, "y"))),
                )
            )
            val lister = FakeTableLister(listOf("a", "b"))
            val exporter = StreamingExporter(reader, lister, RecordingChunkWriterFactory())

            val result = exporter.export(
                pool = pool,
                tables = emptyList(),
                output = ExportOutput.FilePerTable(tmpDir),
                format = DataExportFormat.JSON,
            )

            result.tables.map { it.table } shouldContainExactly listOf("a", "b")
            lister.callCount shouldBe 1
        } finally {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    test("No tables (--tables empty AND TableLister returns empty) throws") {
        val reader = FakeDataReader(emptyMap())
        val lister = FakeTableLister(emptyList())
        val exporter = StreamingExporter(reader, lister, RecordingChunkWriterFactory())

        shouldThrow<IllegalArgumentException> {
            exporter.export(
                pool = pool,
                tables = emptyList(),
                output = ExportOutput.Stdout,
                format = DataExportFormat.JSON,
            )
        }
    }

    // ─── Error isolation ─────────────────────────────────────────

    test("A failing table is captured in the summary, other tables continue") {
        val tmpDir = Files.createTempDirectory("streaming-test-")
        try {
            val reader = FakeDataReader(
                mapOf(
                    "ok" to listOf(chunk("ok", 0, arrayOf<Any?>(1, "alice"))),
                    "broken" to null, // FakeDataReader wirft für null
                    "ok2" to listOf(chunk("ok2", 0, arrayOf<Any?>(2, "bob"))),
                )
            )
            val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())

            val result = exporter.export(
                pool = pool,
                tables = listOf("ok", "broken", "ok2"),
                output = ExportOutput.FilePerTable(tmpDir),
                format = DataExportFormat.JSON,
            )

            result.tables.map { it.table } shouldContainExactly listOf("ok", "broken", "ok2")
            result.tables[0].error shouldBe null
            result.tables[1].error shouldNotBe null
            result.tables[2].error shouldBe null
            result.success shouldBe false
        } finally {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    // ─── Multi-chunk ──────────────────────────────────────────────

    test("Multi-chunk table aggregates rows correctly") {
        val reader = FakeDataReader(
            mapOf(
                "big" to listOf(
                    chunk("big", 0, arrayOf<Any?>(1, "a"), arrayOf<Any?>(2, "b"), arrayOf<Any?>(3, "c")),
                    chunk("big", 1, arrayOf<Any?>(4, "d"), arrayOf<Any?>(5, "e")),
                )
            )
        )
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())

        val result = exporter.export(
            pool = pool,
            tables = listOf("big"),
            output = ExportOutput.Stdout,
            format = DataExportFormat.JSON,
        )

        result.totalRows shouldBe 5
        result.totalChunks shouldBe 2
    }

    // ─── Stdout multi-table is rejected ──────────────────────────

    test("Stdout with multiple tables is rejected") {
        val reader = FakeDataReader(
            mapOf(
                "a" to listOf(chunk("a", 0, arrayOf<Any?>(1, "x"))),
                "b" to listOf(chunk("b", 0, arrayOf<Any?>(2, "y"))),
            )
        )
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())

        shouldThrow<IllegalArgumentException> {
            exporter.export(
                pool = pool,
                tables = listOf("a", "b"),
                output = ExportOutput.Stdout,
                format = DataExportFormat.JSON,
            )
        }
    }

    // ─── PipelineConfig ──────────────────────────────────────────

    test("Custom chunkSize is passed to the reader") {
        val reader = FakeDataReader(
            mapOf("users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "alice"))))
        )
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())

        exporter.export(
            pool = pool,
            tables = listOf("users"),
            output = ExportOutput.Stdout,
            format = DataExportFormat.JSON,
            config = PipelineConfig(chunkSize = 500),
        )

        reader.lastChunkSize shouldBe 500
    }

    test("PipelineConfig validates chunkSize") {
        shouldThrow<IllegalArgumentException> { PipelineConfig(chunkSize = 0) }
        shouldThrow<IllegalArgumentException> { PipelineConfig(chunkSize = -1) }
    }

    // ─── F23: Stdout-Wrapper darf System.out NICHT schließen ─────

})
