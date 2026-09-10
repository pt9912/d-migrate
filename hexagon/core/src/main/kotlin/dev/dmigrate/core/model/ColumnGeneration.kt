package dev.dmigrate.core.model

/**
 * Wie eine Spalte ihren Wert bekommt, wenn nicht durch Schreiben.
 */
sealed interface ColumnGeneration {

    data class Identity(
        val mode: IdentityMode = IdentityMode.BY_DEFAULT,
        val sequenceName: String? = null,
        val legacySerialSyntax: Boolean = false,
    ) : ColumnGeneration

    /**
     * Eine berechnete Spalte: `GENERATED ALWAYS AS (<ausdruck>)`.
     *
     * [expression] ist roher SQL-Text — dasselbe Wesen wie ein CHECK-Ausdruck
     * oder ein Sichten-Rumpf, mit denselben Folgen: der Server gibt ihn nicht
     * wortgleich zurueck, und ein Textvergleich gegen die Autorenform meldete
     * deshalb bei jedem Lauf Drift ([ADR 0053](../../../../../../../../docs/adr/0053-vergleich-rohen-sql-texts.md)).
     *
     * [stored] unterscheidet die beiden Formen, die die Server kennen —
     * gemessen, nicht der Doku entnommen:
     *
     * | Server | `VIRTUAL` | `STORED` | ohne Angabe |
     * | --- | --- | --- | --- |
     * | PostgreSQL | Syntaxfehler | ja | **`STORED` ist Pflicht** |
     * | MySQL, SQLite, Oracle | ja | ja | `VIRTUAL` |
     * | SQL Server | ja | ja (`PERSISTED`) | virtuell |
     *
     * Der Vorgabewert folgt den vier Servern, die eine Vorgabe haben. Fuer
     * PostgreSQL ist das Feld keine Wahl, sondern eine Konstante: dort faltet
     * die Faehigkeits-Projektion des Ziels beide Werte auf `true`, sonst
     * meldete jeder Round-Trip eine Aenderung an einer Spalte, an der sich
     * nichts geaendert hat.
     */
    data class Computed(
        val expression: String,
        val stored: Boolean = false,
    ) : ColumnGeneration
}

enum class IdentityMode {
    ALWAYS,
    BY_DEFAULT,
}
