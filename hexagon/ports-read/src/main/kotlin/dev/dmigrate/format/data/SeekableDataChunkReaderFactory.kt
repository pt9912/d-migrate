package dev.dmigrate.format.data

/**
 * AP10: Erzeugt [DataChunkReader]-Instanzen fuer Formate, die
 * seekbaren Zugriff brauchen (Parquet-Footer und Row-Group-
 * Sprung). Parallel zur stream-basierten
 * [DataChunkReaderFactory] (`docs/planning/done/parquet-libraries.md`
 * §7.1).
 *
 * Aufrufer (TableImporter/CLI-Wiring nach AP12 §5.1) waehlen
 * pro Format zwischen den beiden Factories:
 *
 * - `JSON` / `YAML` / `CSV` -> [DataChunkReaderFactory]
 *   (InputStream).
 * - `PARQUET` -> [SeekableDataChunkReaderFactory] (Pfad ueber
 *   [SeekableChunkSource]).
 *
 * Die konkrete Implementierung
 * (`ParquetSeekableDataChunkReaderFactory`) lebt im
 * Parquet-Adapter (`adapters:driven:formats-parquet`) und
 * wird in S3 dieses Umbrellas geliefert.
 */
interface SeekableDataChunkReaderFactory {

    /**
     * @param format Input-Format. Heute nur `PARQUET`; weitere
     *   seekable Formate koennen ohne Port-Aenderung folgen.
     * @param source Seekbare Quelle. Heute nur
     *   [SeekableChunkSource.Local]; kuenftige Adapter
     *   erweitern die Sealed-Hierarchie ohne Vertragsbruch.
     * @param table Tabellenname (Diagnose).
     * @param schema Bereits aufgeloestes [ChunkSchema] aus dem
     *   AP7/AP8/AP9-Preflight. Der Reader rekonstruiert das
     *   Schema NICHT aus dem Datei-Footer — Schema-Quelle ist
     *   das Preflight-Ergebnis (AP10 §3.3); der Reader macht
     *   nur einen billigen Namens-/Anzahlcheck gegen den
     *   Footer.
     * @param chunkSize Anzahl Zeilen pro `nextChunk()`. Muss
     *   `> 0` sein.
     * @param options Format-/Encoding-Optionen. Fuer Parquet
     *   ist `encoding` ohne Bedeutung; AP12 entscheidet, ob
     *   solche Felder silently ignored oder im CLI-Preflight
     *   abgelehnt werden.
     */
    fun create(
        format: DataExportFormat,
        source: SeekableChunkSource,
        table: String,
        schema: ChunkSchema,
        chunkSize: Int,
        options: FormatReadOptions = FormatReadOptions(),
    ): DataChunkReader
}
