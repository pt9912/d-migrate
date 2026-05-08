package dev.dmigrate.format.data

import java.io.OutputStream

/**
 * Erzeugt [DataChunkWriter]-Instanzen pro Tabelle aus einem
 * [DataExportFormat] + [ExportOptions] + Output-Stream.
 *
 * LF-009 / LF-013: stabile Factory-Schnittstelle fuer JSON-, YAML- und
 * CSV-Writer. Der Streaming-Layer haengt nur an diesem Port und nicht an
 * konkreten Formatimplementierungen.
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
