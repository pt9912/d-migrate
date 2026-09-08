package dev.dmigrate.driver.metadata

import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadSeverity

/**
 * Die Meldungen fuer eine **berechnete** Spalte, die das neutrale Modell nicht
 * fuehrt.
 *
 * `ColumnGeneration` kennt nur `Identity`; fuer `GENERATED ALWAYS AS (…)` gibt
 * es keine Form. Bis es sie gibt, ist der Verlust wenigstens benannt — der
 * Anwender erfaehrt beim Zuruecklesen, dass sein Schema eine Zusicherung
 * enthaelt, die im Modell nicht ankommt.
 *
 * **Eine Stelle fuer alle Dialekte.** SQL Server meldete das als einziger
 * (`R343`); PostgreSQL, MySQL und Oracle schwiegen. Die Meldung hier ist
 * woertlich die von SQL Server, damit aus einer Fassung nicht vier leicht
 * verschiedene werden.
 *
 * **Zwei Codes, weil die Folgen verschieden sind.** Vier Dialekte geben die
 * Spalte zurueck und verlieren nur ihre Berechnung. SQLite blendet sie in
 * `PRAGMA table_info` ganz aus: dort fehlt die **Spalte**, ein
 * `schema generate` erzeugt eine unvollstaendige Tabelle, und jeder Vergleich
 * plant sie erneut als fehlend. Wer die Meldungen filtert, muss die beiden
 * Faelle unterscheiden koennen.
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
