package dev.dmigrate.driver.data

import dev.dmigrate.core.data.DataChunk

/**
 * Single-use [Sequence] über [DataChunk]s + [AutoCloseable].
 *
 * Erlaubt sowohl `for (chunk in stream) { ... }` als auch
 * `stream.use { it.forEach { ... } }`.
 *
 * **Vertrag (siehe implementation-plan-0.3.0.md §6.1):**
 *
 * - Eine `ChunkSequence` darf **genau einmal** iteriert werden. Eine zweite
 *   Iteration wirft `IllegalStateException` — JDBC-Cursors sind nicht
 *   restartable, und die `Sequence`-Default-Semantik ist hier irreführend.
 * - Der Iterator hält eine ausgeliehene JDBC-Connection (autoCommit=false)
 *   und ein offenes ResultSet bis zur Erschöpfung.
 * - Der Caller MUSS die Sequence vollständig konsumieren ODER via `use {}`
 *   schließen, sonst leakt die Connection im Pool. [close] ist idempotent.
 * - [close] führt vor dem Connection-Return immer `rollback()` und
 *   `setAutoCommit(true)` aus (siehe §6.12 Transaktions-Lifecycle).
 */
interface ChunkSequence : Sequence<DataChunk>, AutoCloseable {
    override fun close()
}
