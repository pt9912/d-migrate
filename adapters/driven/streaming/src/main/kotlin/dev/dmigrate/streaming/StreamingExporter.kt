package dev.dmigrate.streaming

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.format.data.DataChunkWriter
import dev.dmigrate.format.data.DataChunkWriterFactory
import dev.dmigrate.format.data.DataExportFormat
import dev.dmigrate.format.data.ExportOptions
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Pull-basierter Streaming-Exporter. Liest Tabellen aus einer DB-Connection
 * und schreibt sie in einen oder mehrere Output-Streams (siehe [ExportOutput]).
 *
 * Plan §2.1 / §6.18 — der Exporter:
 *
 * - **bekommt einen [ConnectionPool]** durchgereicht (kein Connection-Besitz im
 *   Caller — Reader und TableLister borgen sich pro Tabelle eine eigene
 *   Connection)
 * - iteriert über die Tabellen und ruft für jede einen frischen [DataChunkWriter]
 *   aus der [DataChunkWriterFactory] ab
 * - garantiert §6.17 (leere Tabellen): jeder Reader liefert mindestens einen
 *   Chunk; der Writer schreibt für `chunk.rows.isEmpty()` keine Daten, aber
 *   `begin`/`end` werden trotzdem aufgerufen — damit bekommen CSV den Header,
 *   JSON `[]` und YAML `[]`
 * - schließt jeden Writer und seine ChunkSequence ordentlich, auch bei
 *   Exceptions, sodass keine Connections im Pool leaken
 *
 * **Phase C**: kein Checkpoint, keine Parallelisierung, keine Retry-Logik.
 * Diese Features kommen in 0.5.0 / 1.0.0 — hier fokussieren wir uns auf den
 * sauberen Single-threaded-Pfad.
 */
class StreamingExporter(
    private val reader: DataReader,
    private val tableLister: TableLister,
    private val writerFactory: DataChunkWriterFactory,
) {

    /**
     * Exportiert die angegebenen Tabellen in den Output-Sink.
     *
     * @param pool Connection-Pool — Reader und TableLister borgen sich daraus
     *   pro Aufruf selbst Connections (siehe §6.18).
     * @param tables Tabellennamen, oder `emptyList()` um automatisch alle
     *   Tabellen via [TableLister.listTables] zu ermitteln.
     * @param output Wohin geschrieben wird (Stdout, SingleFile, FilePerTable).
     * @param format Output-Format (json/yaml/csv).
     * @param options Format-spezifische Optionen (Encoding, BOM, CSV-Delimiter, …).
     * @param config Pipeline-Konfiguration (chunkSize).
     * @param filter Optionaler Filter — wird auf alle Tabellen angewendet.
     * @return Statistik-Aggregat über alle Tabellen.
     */
    fun export(
        pool: ConnectionPool,
        tables: List<String>,
        output: ExportOutput,
        format: DataExportFormat,
        options: ExportOptions = ExportOptions(),
        config: PipelineConfig = PipelineConfig(),
        filter: DataFilter? = null,
    ): ExportResult {
        val effectiveTables = tables.ifEmpty { tableLister.listTables(pool) }
        require(effectiveTables.isNotEmpty()) {
            "No tables to export — neither --tables given nor any tables found via TableLister."
        }

        val startedAt = System.nanoTime()
        val tableSummaries = mutableListOf<TableExportSummary>()
        var totalBytes = 0L

        when (output) {
            is ExportOutput.Stdout -> {
                require(effectiveTables.size == 1) {
                    "Stdout output supports exactly one table, got ${effectiveTables.size}"
                }
                val table = effectiveTables.single()
                // F23: System.out NIE schließen, sonst zerstören wir stdout für den Rest
                // des Prozesses. Realer DataChunkWriter.close() schließt aber laut Vertrag
                // den darunterliegenden Stream — daher wrappen wir System.out in einen
                // NonClosingOutputStream, der close() ignoriert (flush() weitergereicht).
                val nonClosing = NonClosingOutputStream(System.out)
                val counting = CountingOutputStream(nonClosing)
                val writer = writerFactory.create(format, counting, options)
                try {
                    tableSummaries += exportSingleTable(pool, reader, table, filter, config, writer, counting)
                } finally {
                    runCatching { writer.close() }   // schließt counting → nonClosing → no-op
                    runCatching { System.out.flush() }
                }
                totalBytes += counting.count
            }

            is ExportOutput.SingleFile -> {
                require(effectiveTables.size == 1) {
                    "SingleFile output supports exactly one table, got ${effectiveTables.size}"
                }
                val table = effectiveTables.single()
                Files.newOutputStream(
                    output.path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ).use { fileOut ->
                    BufferedOutputStream(fileOut).use { buffered ->
                        val counting = CountingOutputStream(buffered)
                        val writer = writerFactory.create(format, counting, options)
                        try {
                            tableSummaries += exportSingleTable(pool, reader, table, filter, config, writer, counting)
                        } finally {
                            runCatching { writer.close() }
                        }
                        totalBytes += counting.count
                    }
                }
            }

            is ExportOutput.FilePerTable -> {
                Files.createDirectories(output.directory)
                for (table in effectiveTables) {
                    val path: Path = output.directory.resolve(ExportOutput.fileNameFor(table, format))
                    Files.newOutputStream(
                        path,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE,
                    ).use { fileOut ->
                        BufferedOutputStream(fileOut).use { buffered ->
                            val counting = CountingOutputStream(buffered)
                            val writer = writerFactory.create(format, counting, options)
                            try {
                                tableSummaries += exportSingleTable(pool, reader, table, filter, config, writer, counting)
                            } finally {
                                runCatching { writer.close() }
                            }
                            totalBytes += counting.count
                        }
                    }
                }
            }
        }

        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        return ExportResult(
            tables = tableSummaries,
            totalRows = tableSummaries.sumOf { it.rows },
            totalChunks = tableSummaries.sumOf { it.chunks },
            totalBytes = totalBytes,
            durationMs = durationMs,
        )
    }

    /**
     * Exportiert genau eine Tabelle in den übergebenen Writer. Der Writer
     * wird hier NICHT geschlossen — das macht der Caller im finally-Block.
     *
     * Vertrag-Garantien (F24):
     * - `writer.end()` wird **ausschließlich** dann aufgerufen, wenn vorher
     *   `writer.begin()` erfolgreich gelaufen ist. Sonst könnte ein realer
     *   Format-Writer einen schließenden Delimiter (z.B. JSON `]`) ohne
     *   öffnenden Anfang in den Output schreiben.
     * - Bei Reader-Vertragsverletzung (kein Chunk geliefert, §6.17) wird
     *   `begin/end` NICHT aufgerufen; der Output bleibt leer.
     * - Bei Exception nach `begin()` wird `end()` defensiv versucht, damit
     *   der Writer offene Container schließen kann (z.B. JSON `]`).
     */
    private fun exportSingleTable(
        pool: ConnectionPool,
        reader: DataReader,
        table: String,
        filter: DataFilter?,
        config: PipelineConfig,
        writer: DataChunkWriter,
        counting: CountingOutputStream,
    ): TableExportSummary {
        val tableStart = System.nanoTime()
        var rows = 0L
        var chunks = 0L
        var error: String? = null
        var beginCalled = false
        val bytesBefore = counting.count

        try {
            reader.streamTable(pool, table, filter, config.chunkSize).use { sequence ->
                for (chunk in sequence) {
                    if (!beginCalled) {
                        // §6.17: der erste Chunk hat columns, auch wenn rows leer ist
                        writer.begin(table, chunk.columns)
                        beginCalled = true
                    }
                    writer.write(chunk)
                    rows += chunk.rows.size.toLong()
                    chunks += 1L
                }
                if (!beginCalled) {
                    // Defensiv: sollte nach §6.17 nicht passieren. Reader hat den
                    // Vertrag verletzt — wir lassen den Output absichtlich leer und
                    // rufen weder begin() noch end() auf.
                    error = "Reader returned no chunks for table '$table' " +
                        "(violates Plan §6.17 — empty tables must still emit one chunk)"
                } else {
                    writer.end()
                }
            }
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
            // F24: end() NUR wenn begin() erfolgreich war. Sonst würde ein realer
            // JSON-Writer ein `]` ohne `[` schreiben, ein YAML-Writer einen
            // hängenden Doc-End-Marker, ein CSV-Writer eine fehlende Header-Zeile etc.
            if (beginCalled) {
                runCatching { writer.end() }
            }
        }

        val durationMs = (System.nanoTime() - tableStart) / 1_000_000
        return TableExportSummary(
            table = table,
            rows = rows,
            chunks = chunks,
            bytes = counting.count - bytesBefore,
            durationMs = durationMs,
            error = error,
        )
    }
}

/**
 * Wrapper-OutputStream, der die Anzahl der durchgeschriebenen Bytes mitzählt.
 * Für die [ExportResult.totalBytes]-Statistik. `close()` wird an den
 * darunterliegenden Stream weitergereicht — bei Stdout wird das via
 * [NonClosingOutputStream] abgefangen, sodass `System.out` nie geschlossen
 * wird.
 */
internal class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var count: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        count += 1
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        count += len.toLong()
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}

/**
 * Wrapper-OutputStream, der `close()` zu einem No-Op macht. `flush()` und
 * `write()` werden weitergereicht. Wird im [StreamingExporter] für den
 * Stdout-Branch verwendet, damit ein realer [DataChunkWriter.close]
 * (der laut Vertrag den darunterliegenden Stream schließt) `System.out`
 * NICHT zerstört.
 *
 * Siehe F23 (Plan-Review-Runde 6) — vorher rief der StreamingExporter
 * `writer.close()` direkt auf einem `CountingOutputStream(System.out)`,
 * was bei produktiven Writer-Implementierungen `System.out` schließen
 * würde.
 */
internal class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()
    override fun close() {
        // bewusst no-op — flush() wird im StreamingExporter explizit aufgerufen
    }
}
