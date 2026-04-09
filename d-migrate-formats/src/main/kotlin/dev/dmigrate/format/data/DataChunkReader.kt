package dev.dmigrate.format.data

import dev.dmigrate.core.data.DataChunk

/**
 * Streaming-Reader für [DataChunk]s aus einem Input-Format.
 *
 * Plan: implementation-plan-0.4.0.md §3.5.1 / §6.2 / §6.4.
 *
 * **Vertrag** (spiegelbildlich zu [DataChunkWriter], aber mit
 * bewusster Asymmetrie für den Header-Pfad):
 *
 * 1. [nextChunk] liefert die nächsten bis zu `chunkSize` Rows. Sobald
 *    die Eingabe vollständig konsumiert ist (nach dem letzten Chunk,
 *    oder bei einer leeren selbstbeschreibenden Eingabe wie `[]` in
 *    JSON/YAML), liefert der Reader `null`.
 *
 *    **L1 — chunkSize auf der Factory, nicht pro Aufruf**: `nextChunk()`
 *    nimmt kein Argument. Die Chunk-Größe wird einmalig bei
 *    [DataChunkReaderFactory.create] gesetzt und vom Reader für alle
 *    Folgeaufrufe gespeichert. Das spiegelt die 0.3.0-Streaming-API
 *    (`PipelineConfig.chunkSize`) und verhindert Argument-Drift.
 *
 *    **Anders als der 0.3.0-Writer-Vertrag (§6.17)** gibt es hier KEINE
 *    Pflicht, einen "Empty-Chunk mit Spalten" zu emittieren: der
 *    Reader ist NICHT die autoritative Quelle für Spaltenmetadaten, die
 *    kommen aus dem Target-Schema (§6.4). Bei einer leeren Eingabe darf
 *    `nextChunk()` also sofort `null` zurückgeben.
 *
 * 2. [headerColumns] liefert — falls verfügbar — die file-derived
 *    Header-Spaltennamen. Der `StreamingImporter` nutzt sie, um ein
 *    Header-zu-Target-Mapping zu validieren (§6.4 Punkt 1). Spalten-
 *    **Typen** kommen IMMER aus dem Ziel-JDBC-Schema, nie aus dem
 *    Reader (F43 / §3.5.2).
 *
 *    Nach `create(...)` bzw. spätestens nach dem ersten
 *    [nextChunk]-Aufruf liefert die Methode einen deterministischen
 *    Snapshot der bekannten Header. Für Header-only-CSV darf das auch
 *    dann eine Liste sein, wenn `nextChunk()` sofort `null` liefert.
 *    Für Eingaben ohne Header (`csvNoHeader = true`, leere JSON-/
 *    YAML-Arrays) bleibt der Wert `null`.
 *
 *    **R9**: Für ein leeres erstes Objekt/Mapping in JSON/YAML (`[{}]`,
 *    `[{}, {"a": 1}]`) setzt der Reader `headerColumns()` auf eine
 *    **leere Liste** (nicht `null`). Jede nicht-leere Folge-Row löst
 *    dann den regulären Schema-Mismatch-Pfad aus, statt still
 *    durchzulaufen.
 *
 * 3. [close] schließt den darunterliegenden Input-Stream idempotent.
 *    Ein `close()` ohne vorangegangene `nextChunk()`-Aufrufe ist
 *    erlaubt und gibt nur die Reader-Resourcen frei.
 *
 * Implementierungen kommen in Phase B: `JsonChunkReader` (DSL-JSON
 * Pull-API), `YamlChunkReader` (SnakeYAML Engine Event-API),
 * `CsvChunkReader` (uniVocity `CsvParser`).
 */
interface DataChunkReader : AutoCloseable {

    /**
     * Liest die nächsten bis zu `chunkSize` Rows. Liefert `null`, wenn
     * die Eingabe vollständig konsumiert wurde.
     *
     * Der zurückgelieferte [DataChunk] hat `columns` als file-derived
     * Header-Namen (Typen sind immer `sqlTypeName = null`,
     * `jdbcType = null`). Der Importer normalisiert und reordert die
     * `columns` vor dem Weiterreichen an den Writer (§6.4).
     */
    fun nextChunk(): DataChunk?

    /**
     * Optionale, file-derived Header-Spaltennamen. Siehe Klassen-Kdoc
     * für den Vertrag. Kann `null` sein, wenn die Eingabe keine
     * Header-Information trägt.
     */
    fun headerColumns(): List<String>?

    /** Idempotenter Cleanup. */
    override fun close()
}
