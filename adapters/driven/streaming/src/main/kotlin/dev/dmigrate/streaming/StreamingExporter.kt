package dev.dmigrate.streaming

import dev.dmigrate.core.data.DataChunk
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
                        val summary = exportSingleTable(
                            pool, reader, table, filter, config, writer, counting,
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
                                val summary = exportSingleTable(
                                    pool, reader, table, filter, config, writer, counting,
                                    progressReporter, 1, 1,
                                    resumeMarkers[table], onChunkProcessed,
                                )
                                tableSummaries += summary
                                onTableCompleted(summary)
                            } finally {
                                runCatching { writer.close() }
                            }
                            totalBytes += counting.count
                        }
                    }
                }
            }

            is ExportOutput.FilePerTable -> {
                Files.createDirectories(output.directory)
                // 0.9.0 Phase C.1 §5.4: die Reihenfolge der zu
                // bearbeitenden Tabellen folgt `discoveredTables` — die
                // `tableOrdinal`/`tableCount` in Progress-Events bleibt
                // stabil auch wenn Resume Tabellen ueberspringt (der
                // Runner kennt die Gesamt-Tabellenliste).
                val activeCount = discoveredTables.size
                for ((index, table) in discoveredTables.withIndex()) {
                    if (table in skippedTables) continue
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
                                val summary = exportSingleTable(
                                    pool, reader, table, filter, config, writer, counting,
                                    progressReporter, index + 1, activeCount,
                                    resumeMarkers[table], onChunkProcessed,
                                )
                                tableSummaries += summary
                                onTableCompleted(summary)
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
            operationId = operationId,
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
        reporter: ProgressReporter,
        ordinal: Int,
        tableCount: Int,
        resumeMarker: ResumeMarker?,
        onChunkProcessed: (TableChunkProgress) -> Unit,
    ): TableExportSummary {
        reporter.report(ProgressEvent.ExportTableStarted(table, ordinal, tableCount))

        val tableStart = System.nanoTime()
        var rows = 0L
        var chunks = 0L
        var error: String? = null
        var beginCalled = false
        val bytesBefore = counting.count
        // 0.9.0 Phase C.2 §5.2: Index-Aufloesung der Marker-/Tie-Breaker-
        // Spalten wird beim ersten Chunk einmalig bestimmt, damit wir
        // pro Chunk nur ArrayIndex-Zugriffe brauchen. Case-insensitive,
        // weil `ResultSetMetaData.getColumnLabel` dialektspezifisch
        // Upper/Lower liefert.
        var markerIdx: Int = -1
        var tieIdxs: IntArray = IntArray(0)

        try {
            // Ohne Marker landet der Reader im Legacy-Pfad (kein ORDER
            // BY). Mit Marker wird die 5-Parameter-Variante genutzt,
            // die je nach `position` Fresh-Track oder
            // Resume-From-Position liefert.
            val sequence = if (resumeMarker == null) {
                reader.streamTable(pool, table, filter, config.chunkSize)
            } else {
                reader.streamTable(pool, table, filter, config.chunkSize, resumeMarker)
            }
            sequence.use { seq ->
                for (chunk in seq) {
                    if (!beginCalled) {
                        writer.begin(table, chunk.columns)
                        beginCalled = true
                        if (resumeMarker != null) {
                            markerIdx = chunk.columns.indexOfFirst {
                                it.name.equals(resumeMarker.markerColumn, ignoreCase = true)
                            }
                            tieIdxs = IntArray(resumeMarker.tieBreakerColumns.size) { i ->
                                val col = resumeMarker.tieBreakerColumns[i]
                                chunk.columns.indexOfFirst { it.name.equals(col, ignoreCase = true) }
                            }
                        }
                    }
                    writer.write(chunk)
                    rows += chunk.rows.size.toLong()
                    chunks += 1L
                    reporter.report(ProgressEvent.ExportChunkProcessed(
                        table = table, tableOrdinal = ordinal, tableCount = tableCount,
                        chunkIndex = chunks.toInt(), rowsInChunk = chunk.rows.size.toLong(),
                        rowsProcessed = rows, bytesWritten = counting.count - bytesBefore,
                    ))
                    // 0.9.0 Phase C.2 §5.2: nur nicht-leere Chunks
                    // liefern einen neuen Marker. Ein leerer Chunk
                    // (Empty-Table-Vertrag §6.17) erzeugt keine
                    // Position — Persistenz bleibt am vorigen Stand.
                    if (resumeMarker != null && chunk.rows.isNotEmpty()) {
                        val lastRow = chunk.rows.last()
                        val markerValue = if (markerIdx >= 0) lastRow[markerIdx] else null
                        val tieValues: List<Any?> = if (tieIdxs.isEmpty()) {
                            emptyList()
                        } else {
                            tieIdxs.map { i -> if (i >= 0) lastRow[i] else null }
                        }
                        runCatching {
                            onChunkProcessed(
                                TableChunkProgress(
                                    table = table,
                                    rowsProcessed = rows,
                                    chunksProcessed = chunks,
                                    position = ResumeMarker.Position(
                                        lastMarkerValue = markerValue,
                                        lastTieBreakerValues = tieValues,
                                    ),
                                )
                            )
                        }
                    }
                }
                if (!beginCalled) {
                    error = "Reader returned no chunks for table '$table' " +
                        "(violates Plan §6.17 — empty tables must still emit one chunk)"
                } else {
                    writer.end()
                }
            }
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
            if (beginCalled) {
                runCatching { writer.end() }
            }
        }

        val durationMs = (System.nanoTime() - tableStart) / 1_000_000
        val status = if (error == null) TableProgressStatus.COMPLETED else TableProgressStatus.FAILED
        reporter.report(ProgressEvent.ExportTableFinished(
            table = table, tableOrdinal = ordinal, tableCount = tableCount,
            rowsProcessed = rows, chunksProcessed = chunks.toInt(),
            bytesWritten = counting.count - bytesBefore, durationMs = durationMs,
            status = status,
        ))

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
