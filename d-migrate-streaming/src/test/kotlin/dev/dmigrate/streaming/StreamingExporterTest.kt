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
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
            TableExportSummary(table = "users", rows = 2, chunks = 1, bytes = result.tables.single().bytes, durationMs = result.tables.single().durationMs),
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

    test("Reader that returns zero chunks (violates §6.17) reports an error") {
        val reader = FakeDataReader(mapOf("broken" to emptyList()))
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())

        val result = exporter.export(
            pool = pool,
            tables = listOf("broken"),
            output = ExportOutput.Stdout,
            format = DataExportFormat.JSON,
        )

        result.tables.single().error!!.contains("§6.17") shouldBe true
        result.success shouldBe false
    }

    // ─── DataFilter pass-through ────────────────────────────────

    test("DataFilter is passed through to the reader") {
        val filter = DataFilter.WhereClause("id > 0")
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
})

// ───────────────────────────────────────────────────────────────
// Test Doubles
// ───────────────────────────────────────────────────────────────

private object NoopConnectionPool : ConnectionPool {
    override val dialect = DatabaseDialect.SQLITE
    override fun borrow(): Connection = error("FakeDataReader does not call pool.borrow()")
    override fun activeConnections(): Int = 0
    override fun close() = Unit
}

/**
 * Fake DataReader. Liefert vorbereitete Chunks pro Tabelle. `null`-Werte
 * in der Map signalisieren, dass die Tabelle einen Fehler werfen soll
 * (für Error-Isolation-Tests).
 */
private class FakeDataReader(
    private val tableChunks: Map<String, List<DataChunk>?>,
) : DataReader {
    override val dialect = DatabaseDialect.SQLITE
    var lastFilter: DataFilter? = null
    var lastChunkSize: Int = -1

    override fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
    ): ChunkSequence {
        lastFilter = filter
        lastChunkSize = chunkSize
        val chunks = tableChunks[table] ?: error("FakeDataReader: simulated failure for table '$table'")
        return FakeChunkSequence(chunks)
    }
}

private class FakeChunkSequence(private val chunks: List<DataChunk>) : ChunkSequence {
    private var consumed = false
    private var closed = false

    override fun iterator(): Iterator<DataChunk> {
        check(!consumed) { "ChunkSequence already consumed" }
        check(!closed) { "ChunkSequence is closed" }
        consumed = true
        return chunks.iterator()
    }

    override fun close() {
        closed = true
    }
}

private class FakeTableLister(private val tables: List<String>) : TableLister {
    override val dialect = DatabaseDialect.SQLITE
    var callCount = 0
    override fun listTables(pool: ConnectionPool): List<String> {
        callCount += 1
        return tables
    }
}

/**
 * Fake DataChunkWriter. Records the lifecycle events
 * (`create`/`begin`/`write:N`/`end`/`close`) als globale Liste, sodass
 * Tests die Aufruf-Reihenfolge prüfen können. Schreibt ein einfaches
 * `<table>:<row>\n` als Marker in den Output-Stream.
 */
private class RecordingChunkWriterFactory : DataChunkWriterFactory {
    val events = mutableListOf<String>()

    override fun create(format: DataExportFormat, output: OutputStream, options: ExportOptions): DataChunkWriter {
        return object : DataChunkWriter {
            private var table: String = "?"

            override fun begin(table: String, columns: List<ColumnDescriptor>) {
                this.table = table
                if (events.lastOrNull()?.startsWith("create:") != true) {
                    // Defensive falls Test create vergessen hat
                    events += "create:$table"
                } else {
                    // Aktualisiere "create:?" auf "create:<table>"
                    events[events.lastIndex] = "create:$table"
                }
                events += "begin:$table"
            }

            override fun write(chunk: DataChunk) {
                events += "write:$table:${chunk.rows.size}"
                for (row in chunk.rows) {
                    output.write("$table:${row.joinToString(",")}\n".toByteArray())
                }
            }

            override fun end() {
                events += "end:$table"
            }

            override fun close() {
                events += "close:$table"
            }
        }.also { events += "create:?" }
    }
}
