package dev.dmigrate.driver.data

import dev.dmigrate.core.data.DataChunk

/**
 * Session für den Import einer einzelnen Tabelle. Implementiert eine
 * strikte State-Maschine (M1) mit den Zuständen OPEN, WRITTEN, FAILED,
 * FINISHED, CLOSED.
 *
 * Lifecycle:
 * ```
 * val session = writer.openTable(pool, table, options)
 * session.use {
 *     for (chunk in chunks) {
 *         it.write(chunk)
 *         it.commitChunk()   // oder rollbackChunk() bei Fehler
 *     }
 *     it.finishTable()
 * }
 * ```
 *
 * **F3**: `close()` wirft nicht — Sekundärfehler werden strukturiert
 * gemeldet, nicht als Exception propagiert.
 */
interface TableImportSession : AutoCloseable {

    /**
     * Autoritative Target-Spalten in Binding-Reihenfolge. Vom Writer
     * beim Öffnen der Tabelle über JDBC-Metadaten eingelesen (§6.4).
     */
    val targetColumns: List<TargetColumn>

    /**
     * Schreibt einen Chunk im aktuellen Transaktionskontext.
     *
     * State-Maschine: OPEN → WRITTEN.
     * Aus jedem anderen Zustand: IllegalStateException.
     */
    fun write(chunk: DataChunk): WriteResult

    /**
     * Bestätigt den letzten geschriebenen Chunk.
     *
     * State-Maschine: WRITTEN → OPEN.
     * Wirft commitChunk() selbst (H-R1): WRITTEN → FAILED.
     */
    fun commitChunk()

    /**
     * Verwirft den letzten geschriebenen Chunk.
     *
     * State-Maschine: WRITTEN → OPEN.
     * Wirft rollbackChunk() selbst (H-R1): WRITTEN → FAILED.
     */
    fun rollbackChunk()

    /**
     * Truncate-Signal pro Tabelle (R3). Idempotent.
     *
     * State-Maschine: erlaubt aus OPEN, solange noch kein `write()`
     * stattgefunden hat (auch nicht nach `commitChunk()` zurück in
     * OPEN). Das `hasWritten`-Flag ist sticky.
     * Aus jedem anderen Zustand oder nach erstem write:
     * IllegalStateException.
     */
    fun markTruncatePerformed()

    /**
     * Regulärer Erfolgsabschluss: Reseeding + post-import Cleanup
     * (z. B. Trigger-/FK-Reenable, falls writerseitig im Erfolgsweg
     * abbildbar).
     *
     * State-Maschine: OPEN → FINISHED.
     * 0-Chunk-Pfad (F1) ist gültig (OPEN ohne vorangegangenen write).
     * Aus WRITTEN: IllegalStateException (Importer MUSS vorher committen).
     */
    fun finishTable(): FinishTableResult

    /**
     * Cleanup: Rollback offener Transaktion, autoCommit-Reset,
     * Trigger-Reenable (idempotent), Connection zurückgeben.
     *
     * Wirft NICHT (F3). Idempotent.
     * R6-Cleanup-Reihenfolge: rollback → autoCommit → enableTriggers
     * → FK-Reset → Connection-Return.
     */
    override fun close()
}
