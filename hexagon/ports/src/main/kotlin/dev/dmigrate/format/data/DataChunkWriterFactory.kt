package dev.dmigrate.format.data

import java.io.OutputStream

/**
 * Erzeugt [DataChunkWriter]-Instanzen pro Tabelle aus einem
 * [DataExportFormat] + [ExportOptions] + Output-Stream.
 *
 * Plan §3.5 / Phase D Schritt 18. Die konkreten Writer-Implementierungen
 * (`JsonChunkWriter`, `YamlChunkWriter`, `CsvChunkWriter`) folgen in
 * Phase D — bis dahin gibt es nur dieses Interface plus die
 * Datenstrukturen, damit `d-migrate-streaming` (Phase C) bereits gegen
 * eine stabile Schnittstelle bauen kann.
 */
interface DataChunkWriterFactory {

    /**
     * Liefert einen neuen [DataChunkWriter] für das angegebene Format,
     * der direkt in den übergebenen [OutputStream] schreibt. Der Writer
     * übernimmt die Lifetime des Streams via [DataChunkWriter.close].
     */
    fun create(
        format: DataExportFormat,
        output: OutputStream,
        options: ExportOptions = ExportOptions(),
    ): DataChunkWriter
}
