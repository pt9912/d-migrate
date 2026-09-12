package dev.dmigrate.driver.metadata

import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

/**
 * Die Meldungen fuer eine **berechnete** Spalte, deren Berechnung der Reverse
 * nicht mitbringt.
 *
 * `ColumnGeneration.Computed` fuehrt sie inzwischen, und alle fuenf Dialekte
 * lesen sie. Diese Meldungen sind damit nicht mehr der Normalfall, sondern der
 * **Rueckfall**: wenn der Server den Ausdruck nicht hergibt, wird der Verlust
 * benannt statt geraten.
 *
 * **Eine Stelle fuer alle Dialekte**, damit aus einer Fassung nicht fuenf
 * leicht verschiedene werden.
 *
 * **Drei Codes, weil die Folgen verschieden sind.**
 *
 * | Code | Lage | Folge |
 * | --- | --- | --- |
 * | `R343` | Die Spalte kommt, ihr Ausdruck nicht | Sie wird als gewoehnliche Spalte gelesen |
 * | `R367` | SQLite blendet die Spalte in `PRAGMA table_info` aus | Die **Spalte** fehlt; jeder Vergleich plant sie erneut |
 * | `R369` | Oracle: die Einordnung materialisiert/Default ist nicht lesbar | Eine materialisierte Spalte kaeme als Default durch |
 *
 * Wer die Meldungen filtert, muss die Faelle unterscheiden koennen.
 */
object GeneratedColumnNotes {

    /** Die Spalte kommt zurueck, ihre Berechnung nicht. */
    const val EXPRESSION_DROPPED: String = "R343"

    /** Die Spalte kommt gar nicht zurueck. */
    const val COLUMN_ABSENT: String = "R367"

    fun expressionDropped(table: String, column: String, definition: String?): SchemaReadNote = SchemaReadNote(
        severity = SchemaReadSeverity.ACTION_REQUIRED,
        code = EXPRESSION_DROPPED,
        objectName = "$table.$column",
        message = "Computed column definition ${definition ?: "?"} " +
            "is not carried in the neutral model; the column was read as a plain column.",
        hint = "Recreate the computed expression manually on the target.",
    )

    /**
     * Die Einordnung war nicht entscheidbar — Oracle, ohne `DBMS_METADATA`.
     *
     * Der Katalog fuehrt eine **materialisiert** berechnete Spalte wie eine
     * gewoehnliche mit `DEFAULT` (gemessen: `VIRTUAL_COLUMN = 'NO'`,
     * `USER_GENERATED = 'YES'`, Ausdruck in `DATA_DEFAULT`). Das Wort
     * `MATERIALIZED` steht nur in der DDL. Ist die nicht lesbar, sind die
     * [candidates] genau die Spalten, bei denen beides moeglich ist — geraten
     * wird nicht, gemeldet schon.
     *
     * Die Meldung geht **je Tabelle** hinaus, nicht je Spalte: in der Regel ist
     * keine der Kandidaten berechnet, und eine Meldung pro Default-Spalte waere
     * Laerm ohne Befund.
     */
    const val GENERATION_UNDECIDABLE: String = "R369"

    fun generationUndecidable(table: String, candidates: List<String>): SchemaReadNote = SchemaReadNote(
        severity = SchemaReadSeverity.WARNING,
        code = GENERATION_UNDECIDABLE,
        objectName = table,
        message = "Could not read the table DDL (DBMS_METADATA), so a MATERIALIZED computed column is " +
            "indistinguishable from a plain DEFAULT here: ${candidates.joinToString(", ")}. " +
            "They were read as plain columns with a default.",
        hint = "Grant EXECUTE on DBMS_METADATA to the reading user, or check these columns manually.",
    )

    fun columnAbsent(table: String, column: String, kind: String): SchemaReadNote = SchemaReadNote(
        severity = SchemaReadSeverity.ACTION_REQUIRED,
        code = COLUMN_ABSENT,
        objectName = "$table.$column",
        message = "Column '$column' is a $kind generated column and is NOT part of the reverse output: " +
            "the neutral model has no form for it, and SQLite hides such columns from `PRAGMA table_info`.",
        hint = "A comparison against this database plans the column as missing on every run. Keep the source " +
            "schema as the authority for this table, or recreate the column manually on the target.",
    )
}
