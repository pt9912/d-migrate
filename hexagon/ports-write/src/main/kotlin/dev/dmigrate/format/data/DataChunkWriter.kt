package dev.dmigrate.format.data

import dev.dmigrate.core.data.DataChunk

/**
 * Streaming-Writer für [DataChunk]s in ein Output-Format.
 *
 * LF-009 / LF-013: konkrete Implementierungen schreiben JSON, YAML und CSV
 * mit formatgerechter Empty-Table- und Chunk-Semantik.
 *
 * **Vertrag**:
 * 1. [begin] wird **höchstens einmal** aufgerufen, vor dem ersten [write].
 *    Schreibt z.B. den CSV-Header oder den öffnenden JSON-Array-Bracket.
 * 2. [write] wird für jeden Chunk aufgerufen — auch für leere Chunks (siehe
 *    §6.17). Bei `chunk.rows.isEmpty()` schreibt der Writer nichts (das
 *    ist erlaubt und wird vom StreamingExporter benutzt, um den Reader-
 *    Vertrag der "mindestens ein Chunk pro Tabelle" zu unterstützen).
 *    `write` darf NICHT vor `begin` aufgerufen werden.
 * 3. [end] wird **nur dann** aufgerufen, wenn vorher [begin] erfolgreich
 *    gelaufen ist. Schließt offene Container (z.B. JSON-Array `]`).
 *    Ein realer Writer DARF darauf bauen — der StreamingExporter ruft
 *    `end()` nicht ohne vorheriges `begin()` auf, auch nicht im Fehlerpfad.
 * 4. [close] **darf jederzeit** aufgerufen werden, auch ohne vorheriges
 *    [begin]. Bei `close()` ohne `begin()` schreibt der Writer KEINE
 *    Daten in den Output-Stream und schließt nur seine internen Resourcen
 *    sowie den darunterliegenden Stream. Idempotent.
 *
 * Implementierungen DÜRFEN davon ausgehen, dass die Reihenfolge bei
 * erfolgreichem Export `begin → write* → end → close` ist. Bei Fehlern
 * vor dem ersten Chunk wird ausschließlich `close` aufgerufen. Eine
 * wiederholte `begin`-Aufruf für eine andere Tabelle ist NICHT erlaubt
 * — pro Tabelle eine neue Writer-Instanz aus der [DataChunkWriterFactory].
 */
interface DataChunkWriter : AutoCloseable {

    /**
     * Wird einmal vor dem ersten [write]-Aufruf aufgerufen. Gibt dem
     * Writer das Tabellenschema (AP2 §6.1), mit dem er Header-Strukturen
     * aufbaut (z.B. CSV-Spaltenüberschriften). JSON/YAML/CSV lesen aus
     * [ChunkSchema.columns] nur Name und Nullability; Parquet konsumiert
     * zusaetzlich `neutralType` fuer das `MessageType`-Mapping
     * (AP2 §6.3).
     */
    fun begin(table: String, schema: ChunkSchema)

    /**
     * Schreibt einen Chunk in den Output-Stream. Bei einem leeren Chunk
     * (`rows.isEmpty()`) schreibt der Writer nichts — das ist erlaubt und
     * wird vom StreamingExporter benutzt, um den Empty-Table-Vertrag des
     * Readers (§6.17) durchzureichen.
     */
    fun write(chunk: DataChunk)

    /** Schließt offene Container (z.B. JSON-Array `]`, YAML-Doc-End). */
    fun end()

    /** Schließt den darunterliegenden Output-Stream. Idempotent. */
    override fun close()
}
