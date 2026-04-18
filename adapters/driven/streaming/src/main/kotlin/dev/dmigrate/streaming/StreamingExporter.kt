package dev.dmigrate.streaming

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataReader
import dev.dmigrate.driver.data.ResumeMarker
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
        progressReporter: ProgressReporter = NoOpProgressReporter,
        /**
         * 0.9.0 Phase B (`docs/ImpPlan-0.9.0-B.md` §4.5): stabile
         * `operationId` des Laufs. Runner generieren die ID (UUID) und
         * reichen sie hierher; sie landet sowohl in
         * [ProgressEvent.RunStarted.operationId] als auch in
         * [ExportResult.operationId], damit Resume-Manifest, stderr-
         * Summary und Logs denselben Lauf referenzieren. Default `null`
         * fuer Pre-Phase-B-Callsites (Tests).
         */
        operationId: String? = null,
        /**
         * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §3.1 / §5.3):
         * `true`, wenn der Runner einen vorhandenen Checkpoint-Manifest
         * wiederaufnimmt; `false` fuer einen neuen Lauf. Der
         * `ProgressRenderer` nutzt die Flagge fuer die
         * „Starting run …/Resuming run …"-Anzeige.
         */
        resuming: Boolean = false,
        /**
         * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §5.4): Menge der
         * Tabellen, die in diesem Lauf uebersprungen werden sollen —
         * bei Resume die im Manifest als `COMPLETED` markierten
         * Tabellen. Leer bei neuen Laeufen.
         */
        skippedTables: Set<String> = emptySet(),
        /**
         * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §5.3 / §5.4):
         * Callback, der nach jedem abgeschlossenen Tabellen-Export
         * aufgerufen wird — auch bei leeren Tabellen und im Fehlerfall
         * (dann mit `error != null`). Der Runner fuellt damit das
         * Checkpoint-Manifest fort.
         */
        onTableCompleted: (TableExportSummary) -> Unit = {},
        /**
         * 0.9.0 Phase C.2 (`docs/ImpPlan-0.9.0-C2.md` §5.2 / §5.3):
         * pro Tabelle optionaler [ResumeMarker] fuer Mid-Table-Resume.
         *
         * - **Fehlt** der Eintrag → legacy-Pfad: `streamTable(pool, table,
         *   filter, chunkSize)` ohne Marker-Sortierung.
         * - **Position == null** (Fresh-Track) → Exporter ruft die
         *   5-Parameter-Variante; der Reader erzwingt `ORDER BY
         *   (markerColumn, tieBreakers...)` ohne WHERE-Cascade. Damit
         *   kann ein spaeterer Resume dieselbe Ordnung reproduzieren.
         * - **Position != null** (Resume-From-Position) → strikter
         *   `>`-Cascade-Filter; der Exporter startet dann ab dem
         *   dokumentierten Composite-Marker.
         */
        resumeMarkers: Map<String, ResumeMarker> = emptyMap(),
        /**
         * 0.9.0 Phase C.2 §5.2: Chunk-granularer Fortschritts-Callback
         * fuer Tabellen, die mit einem [ResumeMarker] gestreamt werden.
         * Wird pro erfolgreich geschriebenem, nicht-leeren Chunk
         * aufgerufen — leere Chunks (Empty-Table-Vertrag §6.17) loesen
         * **keinen** Callback aus, damit der Runner keine gueltige
         * `Position` mit `null`-Werten persistiert.
         *
         * Der Runner nutzt dieses Signal, um das Manifest nach jedem
         * Chunk mit dem neuen `lastMarker`-Position-Tupel zu
         * aktualisieren. Schreibfehler des Callbacks sollten den
         * Export-Lauf **nicht** abbrechen.
         */
        onChunkProcessed: (TableChunkProgress) -> Unit = {},
    ): ExportResult {
        val discoveredTables = tables.ifEmpty { tableLister.listTables(pool) }
        // 0.9.0 Phase C.1 §5.4: uebersprungene Tabellen werden zwar
        // nicht exportiert, zaehlen aber fuer die Gesamtzahl der
        // Tabellen und tauchen als pre-bestaetigte Summaries in
        // [ExportResult] auf, damit Kennzahlen und Manifest-State
        // nachvollziehbar bleiben.
        val effectiveTables = discoveredTables.filter { it !in skippedTables }
        require(discoveredTables.isNotEmpty()) {
            "No tables to export — neither --tables given nor any tables found via TableLister."
        }

        progressReporter.report(ProgressEvent.RunStarted(
            operation = ProgressOperation.EXPORT,
            totalTables = discoveredTables.size,
            operationId = operationId,
            resuming = resuming,
        ))

        val startedAt = System.nanoTime()
        val tableExporter = TableExporter(reader)
        val tableSummaries = mutableListOf<TableExportSummary>()
        var totalBytes = 0L

        when (output) {
            is ExportOutput.Stdout -> {
                require(discoveredTables.size == 1) {
                    "Stdout output supports exactly one table, got ${discoveredTables.size}"
                }
                if (effectiveTables.isNotEmpty()) {
                    val table = effectiveTables.single()
                    val nonClosing = NonClosingOutputStream(System.out)
                    val counting = CountingOutputStream(nonClosing)
                    val writer = writerFactory.create(format, counting, options)
                    try {
                        val summary = tableExporter.export(
                            pool, table, filter, config, writer, counting,
                            progressReporter, 1, 1,
                            resumeMarkers[table], onChunkProcessed,
                        )
                        tableSummaries += summary
                        onTableCompleted(summary)
                    } finally {
                        runCatching { writer.close() }
                        runCatching { System.out.flush() }
                    }
                    totalBytes += counting.count
                }
            }

            is ExportOutput.SingleFile -> {
                require(discoveredTables.size == 1) {
                    "SingleFile output supports exactly one table, got ${discoveredTables.size}"
                }
                if (effectiveTables.isNotEmpty()) {
                    val table = effectiveTables.single()
                    exportToFile(output.path, format, options) { counting, writer ->
                        val summary = tableExporter.export(
                            pool, table, filter, config, writer, counting,
                            progressReporter, 1, 1,
                            resumeMarkers[table], onChunkProcessed,
                        )
                        tableSummaries += summary
                        onTableCompleted(summary)
                        totalBytes += counting.count
                    }
                }
            }

            is ExportOutput.FilePerTable -> {
                Files.createDirectories(output.directory)
                val activeCount = discoveredTables.size
                for ((index, table) in discoveredTables.withIndex()) {
                    if (table in skippedTables) continue
                    val path = output.directory.resolve(ExportOutput.fileNameFor(table, format))
                    exportToFile(path, format, options) { counting, writer ->
                        val summary = tableExporter.export(
                            pool, table, filter, config, writer, counting,
                            progressReporter, index + 1, activeCount,
                            resumeMarkers[table], onChunkProcessed,
                        )
                        tableSummaries += summary
                        onTableCompleted(summary)
                        totalBytes += counting.count
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
            operationId = operationId,
        )
    }

    private inline fun exportToFile(
        path: Path,
        format: DataExportFormat,
        options: ExportOptions,
        block: (CountingOutputStream, DataChunkWriter) -> Unit,
    ) {
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
                    block(counting, writer)
                } finally {
                    runCatching { writer.close() }
                }
            }
        }
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
