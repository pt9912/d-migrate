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
}
