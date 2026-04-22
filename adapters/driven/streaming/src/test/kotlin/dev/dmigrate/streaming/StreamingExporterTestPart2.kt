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
class StreamingExporterTestPart2 : FunSpec({

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


    test("F23: real-style writer.close() does NOT close System.out") {
        // Wir tauschen System.out gegen einen Spy, der closeCallCount trackt.
        val spy = SpyOutputStream()
        val originalOut = System.out
        System.setOut(java.io.PrintStream(spy, /* autoFlush = */ true))
        try {
            val reader = FakeDataReader(
                mapOf("users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "alice"))))
            )
            // RealStreamClosingChunkWriterFactory simuliert echte Writer:
            // close() schließt den darunterliegenden OutputStream.
            val factory = RealStreamClosingChunkWriterFactory()
            val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), factory)

            exporter.export(
                pool = pool,
                tables = listOf("users"),
                output = ExportOutput.Stdout,
                format = DataExportFormat.JSON,
            )

            // Der Writer hat close() auf seinen OutputStream weitergegeben — und der
            // war ein NonClosingOutputStream(System.out). System.out selbst darf NICHT
            // geschlossen worden sein.
            spy.closeCallCount shouldBe 0
            // Nach dem Export muss System.out weiter benutzbar sein
            spy.bytesWritten shouldNotBe 0L
        } finally {
            System.setOut(originalOut)
        }
    }

    test("F23: SingleFile-Branch closes the file stream as expected") {
        // Sanity-Check: für File-Output sollen Streams normal geschlossen werden
        val tmp = Files.createTempFile("streaming-test-f23-", ".json")
        try {
            val reader = FakeDataReader(
                mapOf("users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "alice"))))
            )
            val exporter = StreamingExporter(
                reader,
                FakeTableLister(emptyList()),
                RealStreamClosingChunkWriterFactory(),
            )

            exporter.export(
                pool = pool,
                tables = listOf("users"),
                output = ExportOutput.SingleFile(tmp),
                format = DataExportFormat.JSON,
            )

            // Datei wurde geschrieben und kann gelesen werden — das funktioniert nur
            // wenn die OutputStreams ordentlich geschlossen wurden
            tmp.readText() shouldContain "alice"
        } finally {
            tmp.deleteIfExists()
        }
    }

    // ─── F24: end() darf nur nach erfolgreichem begin() laufen ───

    test("F24: §6.17 violation (Reader emits zero chunks) → begin and end are NOT called") {
        val reader = FakeDataReader(mapOf("broken" to emptyList()))
        val factory = RecordingChunkWriterFactory()
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), factory)

        exporter.export(
            pool = pool,
            tables = listOf("broken"),
            output = ExportOutput.Stdout,
            format = DataExportFormat.JSON,
        )

        // create und close MÜSSEN aufgerufen werden (Resource-Cleanup),
        // begin/write/end DÜRFEN NICHT laufen.
        factory.events.filter { it.startsWith("create:") }.size shouldBe 1
        factory.events.filter { it.startsWith("close:") }.size shouldBe 1
        factory.events.any { it.startsWith("begin:") } shouldBe false
        factory.events.any { it.startsWith("write:") } shouldBe false
        factory.events.any { it.startsWith("end:") } shouldBe false
    }

    test("F24: Exception in begin() → end() is NOT called") {
        val reader = FakeDataReader(
            mapOf("users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "alice"))))
        )
        // Writer schlägt in begin() fehl, end() würde sonst einen halben Container schreiben
        val factory = FailingBeginChunkWriterFactory()
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), factory)

        val result = exporter.export(
            pool = pool,
            tables = listOf("users"),
            output = ExportOutput.Stdout,
            format = DataExportFormat.JSON,
        )

        result.tables.single().error shouldNotBe null
        factory.events.any { it == "begin-throw" } shouldBe true
        factory.events.any { it == "end" } shouldBe false
        factory.events.any { it == "close" } shouldBe true   // Resource-Cleanup MUSS laufen
    }

    test("F24: Exception in write() AFTER successful begin() → end() IS called for cleanup") {
        // Wenn begin() bereits durchlief und write() später failt, MUSS end()
        // defensiv aufgerufen werden — der Writer kann z.B. ein offenes JSON-Array
        // schließen müssen.
        val reader = FakeDataReader(
            mapOf("users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "alice"))))
        )
        val factory = FailingWriteChunkWriterFactory()
        val exporter = StreamingExporter(reader, FakeTableLister(emptyList()), factory)

        val result = exporter.export(
            pool = pool,
            tables = listOf("users"),
            output = ExportOutput.Stdout,
            format = DataExportFormat.JSON,
        )

        result.tables.single().error shouldNotBe null
        factory.events shouldContainExactly listOf("begin", "write-throw", "end", "close")
    }

    // ─── Progress Event Tests (§8.1) ────────────────────────────────

    // §8.1 #1
    test("progress: emits run started for effective tables") {
        val reader = FakeDataReader(mapOf(
            "a" to listOf(emptyChunk("a")),
            "b" to listOf(emptyChunk("b")),
        ))
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }
        val dir = Files.createTempDirectory("evt")

        StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())
            .export(pool = pool, tables = listOf("a", "b"),
                output = ExportOutput.FilePerTable(dir), format = DataExportFormat.JSON,
                progressReporter = reporter)

        val runStarted = events.filterIsInstance<ProgressEvent.RunStarted>()
        runStarted.size shouldBe 1
        runStarted[0].operation shouldBe ProgressOperation.EXPORT
        runStarted[0].totalTables shouldBe 2
    }

    // §8.1 #2
    test("progress: emits started chunk progress and finished for single table") {
        val reader = FakeDataReader(mapOf(
            "users" to listOf(chunk("users", 0, arrayOf<Any?>(1, "a")))
        ))
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }

        StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())
            .export(pool = pool, tables = listOf("users"),
                output = ExportOutput.Stdout, format = DataExportFormat.JSON,
                progressReporter = reporter)

        val types = events.map { it::class.simpleName }
        types shouldContainExactly listOf("RunStarted", "ExportTableStarted", "ExportChunkProcessed", "ExportTableFinished")

        val finished = events.filterIsInstance<ProgressEvent.ExportTableFinished>().single()
        finished.status shouldBe TableProgressStatus.COMPLETED
        finished.rowsProcessed shouldBe 1
    }

    // §8.1 #3
    test("progress: empty table still emits chunk progress with zero rows") {
        val reader = FakeDataReader(mapOf("empty" to listOf(emptyChunk("empty"))))
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }

        StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())
            .export(pool = pool, tables = listOf("empty"),
                output = ExportOutput.Stdout, format = DataExportFormat.JSON,
                progressReporter = reporter)

        val chunk = events.filterIsInstance<ProgressEvent.ExportChunkProcessed>().single()
        chunk.rowsInChunk shouldBe 0
        chunk.rowsProcessed shouldBe 0
    }

    // §8.1 #4
    test("progress: multi table export emits deterministic ordinals") {
        val reader = FakeDataReader(mapOf(
            "a" to listOf(emptyChunk("a")),
            "b" to listOf(emptyChunk("b")),
            "c" to listOf(emptyChunk("c")),
        ))
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }
        val dir = Files.createTempDirectory("ord")

        StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())
            .export(pool = pool, tables = listOf("a", "b", "c"),
                output = ExportOutput.FilePerTable(dir), format = DataExportFormat.JSON,
                progressReporter = reporter)

        val starts = events.filterIsInstance<ProgressEvent.ExportTableStarted>()
        starts.map { it.tableOrdinal } shouldContainExactly listOf(1, 2, 3)
        starts.map { it.tableCount } shouldContainExactly listOf(3, 3, 3)
    }

    // §8.1 #5
    test("progress: failed table emits finished event with failed status") {
        val reader = FakeDataReader(mapOf("fail" to emptyList()))
        val events = mutableListOf<ProgressEvent>()
        val reporter = ProgressReporter { events += it }

        StreamingExporter(reader, FakeTableLister(emptyList()), RecordingChunkWriterFactory())
            .export(pool = pool, tables = listOf("fail"),
                output = ExportOutput.Stdout, format = DataExportFormat.JSON,
                progressReporter = reporter)

        val finished = events.filterIsInstance<ProgressEvent.ExportTableFinished>().single()
        finished.status shouldBe TableProgressStatus.FAILED
    }
})

// ───────────────────────────────────────────────────────────────
// Test Doubles
// ───────────────────────────────────────────────────────────────

internal object NoopConnectionPool : ConnectionPool {
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
internal class FakeDataReader(
    private val tableChunks: Map<String, List<DataChunk>?>,
) : DataReader {
    override val dialect = DatabaseDialect.SQLITE
    var lastFilter: DataFilter? = null
    var lastChunkSize: Int = -1
    /**
     * 0.9.0 Phase C.2 Test-Support: wurden per-table Resume-Marker
     * reingereicht? `null` = 4-Param-Pfad; non-null = 5-Param-Pfad.
     */
    val lastResumeMarkers: MutableMap<String, dev.dmigrate.driver.data.ResumeMarker?> = mutableMapOf()

    override fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
    ): ChunkSequence {
        lastFilter = filter
        lastChunkSize = chunkSize
        lastResumeMarkers[table] = null
        val chunks = tableChunks[table] ?: error("FakeDataReader: simulated failure for table '$table'")
        return FakeChunkSequence(chunks)
    }

    override fun streamTable(
        pool: ConnectionPool,
        table: String,
        filter: DataFilter?,
        chunkSize: Int,
        resumeMarker: dev.dmigrate.driver.data.ResumeMarker?,
    ): ChunkSequence {
        lastFilter = filter
        lastChunkSize = chunkSize
        lastResumeMarkers[table] = resumeMarker
        val chunks = tableChunks[table] ?: error("FakeDataReader: simulated failure for table '$table'")
        return FakeChunkSequence(chunks)
    }
}

internal class FakeChunkSequence(private val chunks: List<DataChunk>) : ChunkSequence {
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

internal class FakeTableLister(private val tables: List<String>) : TableLister {
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
 *
 * Wichtig: `close()` darf laut DataChunkWriter-Vertrag auch ohne vorheriges
 * `begin()` aufgerufen werden — der Recorder benutzt dann `close:?` als
 * Marker, weil der Tabellenname unbekannt ist.
 */
internal class RecordingChunkWriterFactory : DataChunkWriterFactory {
    val events = mutableListOf<String>()

    override fun create(format: DataExportFormat, output: OutputStream, options: ExportOptions): DataChunkWriter {
        return object : DataChunkWriter {
            private var table: String = "?"

            override fun begin(table: String, columns: List<ColumnDescriptor>) {
                this.table = table
                if (events.lastOrNull() == "create:?") {
                    // Aktualisiere "create:?" auf "create:<table>"
                    events[events.lastIndex] = "create:$table"
                } else {
                    events += "create:$table"
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

/**
 * Spy-OutputStream der die Anzahl der `close()`-Aufrufe und der geschriebenen
 * Bytes trackt. Für F23-Tests: verifiziert, dass `System.out` NIE geschlossen
 * wird, auch wenn der Writer seinen Stream eigentlich schließen würde.
 */
internal class SpyOutputStream : OutputStream() {
    var closeCallCount: Int = 0
    var bytesWritten: Long = 0L
    private val sink = java.io.ByteArrayOutputStream()

    override fun write(b: Int) {
        sink.write(b)
        bytesWritten += 1
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        sink.write(b, off, len)
        bytesWritten += len.toLong()
    }
    override fun flush() = sink.flush()
    override fun close() {
        closeCallCount += 1
    }
}

/**
 * Realistischer DataChunkWriter, der seinen [OutputStream] in `close()`
 * tatsächlich schließt — wie es echte JSON/YAML/CSV-Writer aus Phase D
 * tun werden. Für F23-Tests, um sicherzustellen, dass der StreamingExporter
 * den Stdout-Stream NICHT zerstört.
 */
internal class RealStreamClosingChunkWriterFactory : DataChunkWriterFactory {
    override fun create(format: DataExportFormat, output: OutputStream, options: ExportOptions): DataChunkWriter {
        return object : DataChunkWriter {
            override fun begin(table: String, columns: List<ColumnDescriptor>) {
                output.write("[\n".toByteArray())
            }
            override fun write(chunk: DataChunk) {
                for (row in chunk.rows) {
                    output.write("  ${row.joinToString(",")}\n".toByteArray())
                }
            }
            override fun end() {
                output.write("]\n".toByteArray())
            }
            override fun close() {
                output.close()  // Real-world Writer-Verhalten: schließt den Stream
            }
        }
    }
}

/**
 * Writer der in `begin()` failt. Verifiziert F24: `end()` darf nicht
 * gerufen werden, weil `begin()` nicht erfolgreich war.
 */
internal class FailingBeginChunkWriterFactory : DataChunkWriterFactory {
    val events = mutableListOf<String>()

    override fun create(format: DataExportFormat, output: OutputStream, options: ExportOptions): DataChunkWriter {
        return object : DataChunkWriter {
            override fun begin(table: String, columns: List<ColumnDescriptor>) {
                events += "begin-throw"
                throw RuntimeException("begin failed for $table")
            }
            override fun write(chunk: DataChunk) {
                events += "write"
            }
            override fun end() {
                events += "end"
            }
            override fun close() {
                events += "close"
            }
        }
    }
}

/**
 * Writer der in `write()` failt — nach erfolgreichem `begin()`. Verifiziert
 * F24: `end()` MUSS hier doch gerufen werden (defensive cleanup, der Writer
 * kann offene Container schließen müssen).
 */
internal class FailingWriteChunkWriterFactory : DataChunkWriterFactory {
    val events = mutableListOf<String>()

    override fun create(format: DataExportFormat, output: OutputStream, options: ExportOptions): DataChunkWriter {
        return object : DataChunkWriter {
            override fun begin(table: String, columns: List<ColumnDescriptor>) {
                events += "begin"
            }
            override fun write(chunk: DataChunk) {
                events += "write-throw"
                throw RuntimeException("write failed")
            }
            override fun end() {
                events += "end"
            }
            override fun close() {
                events += "close"
            }
        }
    }
}
