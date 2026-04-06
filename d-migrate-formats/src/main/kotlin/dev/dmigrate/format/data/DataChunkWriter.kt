package dev.dmigrate.format.data

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk

/**
 * Streaming-Writer für [DataChunk]s in ein Output-Format.
 *
 * Plan §3.5 / §6.17. Konkrete Implementierungen kommen in Phase D
 * (`JsonChunkWriter` mit DSL-JSON, `YamlChunkWriter` mit SnakeYAML Engine,
 * `CsvChunkWriter` mit uniVocity-parsers — siehe §11.5).
 *
 * **Vertrag**:
 * 1. [begin] wird genau einmal aufgerufen, vor dem ersten [write].
 *    Schreibt z.B. den CSV-Header oder den öffnenden JSON-Array-Bracket.
 * 2. [write] wird für jeden Chunk aufgerufen — auch für leere Chunks (siehe
 *    §6.17). Bei `chunk.rows.isEmpty()` schreibt der Writer nichts (das
 *    ist erlaubt und wird vom StreamingExporter benutzt, um den Reader-
 *    Vertrag der "mindestens ein Chunk pro Tabelle" zu unterstützen).
 * 3. [end] wird genau einmal nach dem letzten [write] aufgerufen. Schließt
 *    offene Container (z.B. JSON-Array `]`).
 * 4. [close] schließt den darunterliegenden Output-Stream. Idempotent.
 *
 * Implementierungen DÜRFEN davon ausgehen, dass die Reihenfolge
 * `begin → write* → end → close` ist. Eine wiederholte `begin`-Aufruf für
 * eine andere Tabelle ist NICHT erlaubt — pro Tabelle eine neue Writer-
 * Instanz aus der [DataChunkWriterFactory].
 */
interface DataChunkWriter : AutoCloseable {

    /**
     * Wird einmal vor dem ersten [write]-Aufruf aufgerufen. Gibt dem Writer
     * die Tabellenmetadaten, mit denen er Header-Strukturen aufbauen kann
     * (z.B. CSV-Spaltenüberschriften).
     */
    fun begin(table: String, columns: List<ColumnDescriptor>)

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
