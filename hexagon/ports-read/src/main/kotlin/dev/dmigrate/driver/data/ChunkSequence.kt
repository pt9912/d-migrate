package dev.dmigrate.driver.data

import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.format.data.ChunkSchema

/**
 * Single-use [Sequence] über [DataChunk]s + [AutoCloseable].
 *
 * Erlaubt sowohl `for (chunk in stream) { ... }` als auch
 * `stream.use { it.forEach { ... } }`.
 *
 * **Vertrag (LF-008 / LN-010):**
 *
 * - Eine `ChunkSequence` darf **genau einmal** iteriert werden. Eine zweite
 *   Iteration wirft `IllegalStateException` — JDBC-Cursors sind nicht
 *   restartable, und die `Sequence`-Default-Semantik ist hier irreführend.
 * - Der Iterator hält eine ausgeliehene JDBC-Connection (autoCommit=false)
 *   und ein offenes ResultSet bis zur Erschöpfung.
 * - Der Caller MUSS die Sequence vollständig konsumieren ODER via `use {}`
 *   schließen, sonst leakt die Connection im Pool. [close] ist idempotent.
 * - [close] führt vor dem Connection-Return immer `rollback()` und
 *   `setAutoCommit(true)` aus.
 * - [schema] ist **vor** der ersten Iteration verfuegbar (Parquet Cut A
 *   S0b, AP2 §6.4): der Reader baut `ChunkSchema` aus den
 *   `ResultSetMetaData` der Exportquery, bevor Rows fliessen, und der
 *   `StreamingExporter` reicht das Schema an `DataChunkWriter.begin`
 *   durch.
 */
interface ChunkSequence : Sequence<DataChunk>, AutoCloseable {
    /**
     * AP2 §6.4: Tabellenschema, das der Reader vor dem ersten Chunk aus
     * den JDBC-`ResultSetMetaData` (plus optional `SchemaReader`) baut.
     * `StreamingExporter` ruft es vor `DataChunkWriter.begin` ab.
     */
    val schema: ChunkSchema

    override fun close()
}
