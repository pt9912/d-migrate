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

    companion object {
        /**
         * Review-Finding D5: Sentinel-Factory fuer Aufrufer, die
         * [SeekableDataChunkReaderFactory] aus API-Gruenden konstruieren
         * muessen, aber bewusst nicht unterstuetzen (heute: MCP-Import,
         * der noch kein Parquet exponiert). Wirft beim ersten `create`-Call
         * eine [UnsupportedOperationException] mit dem konfigurierten
         * [reason]. Diskoverabel direkt am Port-Vertrag, ohne dass
         * Konsumenten eine Adapter-Klasse importieren muessen.
         */
        fun unsupported(reason: String): SeekableDataChunkReaderFactory =
            UnsupportedSeekableDataChunkReaderFactory(reason)
    }
}

/**
 * Package-private (per Konvention via Top-Level-Position im Port-Modul)
 * Adapter-Klasse hinter [SeekableDataChunkReaderFactory.unsupported].
 * Direkter Import in Anwender-Modulen ist nicht noetig; sie konstruieren
 * Instanzen ueber die Companion-Factory.
 */
private class UnsupportedSeekableDataChunkReaderFactory(
    private val reason: String,
) : SeekableDataChunkReaderFactory {

    override fun create(
        format: DataExportFormat,
        source: SeekableChunkSource,
        table: String,
        schema: ChunkSchema,
        chunkSize: Int,
        options: FormatReadOptions,
    ): DataChunkReader {
        throw UnsupportedOperationException(
            "SeekableDataChunkReaderFactory.create invoked but not supported: $reason"
        )
    }
}
