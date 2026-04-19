package dev.dmigrate.driver.data

import dev.dmigrate.core.data.ColumnDescriptor
import java.sql.Connection

/**
 * Dialekt-spezifische Operationen, die der Writer rund um den Schreib-Zyklus
 * ausführt, aber bewusst nicht in den eigentlichen Chunk-Insert-Pfad mischt.
 *
 * Der [DataWriter] liefert diese Hilfsfunktionen über [DataWriter.schemaSync].
 * Die konkrete Implementierung ist daher strikt dialektabhängig, der
 * Aufrufzeitpunkt aber durch den Import-Vertrag fest vorgegeben.
 */
interface SchemaSync {
    /**
     * Führt nach erfolgreichem Abschluss aller Chunks einer Tabelle die
     * Generator-/Sequence-Nachführung aus.
     *
     * Vertrag:
     * - wird nur im Erfolgsabschluss aufgerufen, nie aus dem Fehlerpfad
     *   `close()`
     * - ermittelt pro importierter Generator-Spalte den höchsten relevanten
     *   Zielwert und führt den Generator darauf nach
     * - ein `MAX(...) = NULL` ist im Standardfall ein expliziter No-op ohne
     *   [SequenceAdjustment]
     * - MySQL/SQLite dürfen nach einem vorherigen Truncate auf leer gebliebener
     *   Tabelle einen expliziten Reset auf den Startwert durchführen
     *
     * Die Rückgabe ist ausschließlich für den Import-Report bestimmt. Jedes
     * [SequenceAdjustment.newValue] beschreibt den nächsten von der Datenbank
     * ohne expliziten Generatorwert auszugebenden Wert, nicht den internen
     * DB-Zustand einer konkreten Implementierung.
     *
     * Fehler werden unverändert an den Caller propagiert.
     */
    fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment>

    /**
     * Deaktiviert user-seitige Trigger für den Import einer Tabelle.
     *
     * Wird writer-intern aus `openTable(...)` vor dem ersten Chunk aufgerufen,
     * wenn `triggerMode = disable` ist.
     *
     * Vertrag:
     * - Dialekte ohne sicheren Disable-Pfad werfen
     *   [UnsupportedTriggerModeException]
     * - auf PostgreSQL läuft der Aufruf außerhalb der Chunk-Transaktionen in
     *   einer eigenen Mini-Transaktion
     */
    fun disableTriggers(conn: Connection, table: String)

    /**
     * Führt den Sicherheits-Pre-Flight für `triggerMode = strict` aus.
     *
     * Der Aufruf verändert den Trigger-Zustand nicht. Stattdessen bricht er mit
     * einer klaren Exception ab, wenn auf der Zieltabelle User-Trigger
     * vorhanden sind.
     */
    fun assertNoUserTriggers(conn: Connection, table: String)

    /**
     * Reaktiviert zuvor deaktivierte Trigger.
     *
     * Vertrag:
     * - wird beim erfolgreichen Tabellenabschluss und im Cleanup-Pfad
     *   `close()` verwendet
     * - muss idempotent sein
     * - Fehler sind harte Fehler und werden nicht geschluckt
     * - auf PostgreSQL läuft der Aufruf symmetrisch zu [disableTriggers] in
     *   einer eigenen Mini-Transaktion außerhalb der Chunk-Transaktionen
     */
    fun enableTriggers(conn: Connection, table: String)
}
