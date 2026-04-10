package dev.dmigrate.format.data

import dev.dmigrate.driver.data.ImportOptions
import java.io.InputStream

/**
 * Erzeugt [DataChunkReader]-Instanzen pro Tabelle aus einem
 * [DataExportFormat] + [InputStream] + [ImportOptions].
 *
 * Plan: implementation-plan-0.4.0.md §3.5.1 / Phase B Schritt 10. Die
 * konkreten Reader-Implementierungen (`JsonChunkReader`, `YamlChunkReader`,
 * `CsvChunkReader`) folgen in Phase B — bis dahin gibt es nur dieses
 * Interface plus die Datenstrukturen, damit der `StreamingImporter`
 * (Phase D) bereits gegen eine stabile Schnittstelle bauen kann.
 */
interface DataChunkReaderFactory {

    /**
     * Liefert einen neuen [DataChunkReader] für das angegebene Format,
     * der aus dem übergebenen [InputStream] liest. Der Reader übernimmt
     * die Lifetime des Streams via [DataChunkReader.close].
     *
     * **L1 — `chunkSize` lebt auf der Factory**: Der Wert wird einmal
     * beim `create(...)` durchgereicht und vom Reader für alle
     * `nextChunk()`-Aufrufe gespeichert. Der `StreamingImporter` ruft
     * `create(...)` mit `chunkSize = config.chunkSize` aus dem
     * [dev.dmigrate.streaming.PipelineConfig], identisch zum 0.3.0-
     * Exportpfad.
     *
     * @param format Input-Format (JSON / YAML / CSV). Muss vom CLI
     *   vor dem Aufruf aus `--format` oder der Dateiendung abgeleitet
     *   sein (§6.3).
     * @param input Rohdaten-InputStream. Der Reader wrappt ihn ggf. in
     *   einen [EncodingDetector]-PushbackStream, wenn
     *   `options.encoding == null` (Auto-Mode).
     * @param table Tabellenname für Debug-/Fehlermeldungen. Der Reader
     *   benutzt das NICHT für SQL-Zwecke — der Importer kennt den
     *   Target-Tabellennamen bereits aus `ImportInput`.
     * @param chunkSize Anzahl Rows pro `nextChunk()`-Rückgabe. Muss
     *   `> 0` sein.
     * @param options Verhalten von CSV-Parsing, Encoding, Null-Sentinel
     *   etc. Siehe [ImportOptions].
     */
    fun create(
        format: DataExportFormat,
        input: InputStream,
        table: String,
        chunkSize: Int,
        options: ImportOptions = ImportOptions(),
    ): DataChunkReader
}
