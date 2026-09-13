package dev.dmigrate.driver.mysql

/**
 * Was `information_schema.columns.EXTRA` ueber die Berechnung einer Spalte
 * sagt -- an einer Stelle, weil Lese- und Schreibpfad dieselbe Frage stellen.
 *
 * **Nicht „enthaelt GENERATED".** MySQL setzt `EXTRA` auch auf
 * `DEFAULT_GENERATED`, und zwar fuer eine Spalte mit Default-**Ausdruck**
 * (`DEFAULT CURRENT_TIMESTAMP`, `DEFAULT (UUID())`; ab 8.0.13). Eine solche
 * Spalte ist gewoehnlich beschreibbar — sie als berechnet zu lesen hiesse, im
 * Reverse einen Verlust zu melden, den es nicht gibt, und im Import ein
 * gueltiges Schreiben abzulehnen. Berechnete Spalten tragen
 * `VIRTUAL GENERATED` bzw. `STORED GENERATED`.
 */
internal object MysqlGeneratedColumns {

    fun isGenerated(extra: String): Boolean = isVirtual(extra) || isStored(extra)

    /**
     * Ist der Default dieser Spalte ein **Ausdruck** und kein Literal?
     *
     * Dasselbe Feld, andere Frage: `DEFAULT_GENERATED` steht fuer einen
     * Default-Ausdruck (`CURRENT_TIMESTAMP`, `(UUID())`, `(1 + 2)`). Fehlt es,
     * ist `COLUMN_DEFAULT` ein Literal — auch wenn es wie ein Aufruf aussieht
     * (`\'UPPER(a)\'`).
     */
    fun isDefaultExpression(extra: String): Boolean = extra.contains(DEFAULT_GENERATED, ignoreCase = true)

    fun isStored(extra: String): Boolean = extra.contains(STORED, ignoreCase = true)

    private fun isVirtual(extra: String): Boolean = extra.contains(VIRTUAL, ignoreCase = true)

    private const val STORED = "STORED GENERATED"
    private const val VIRTUAL = "VIRTUAL GENERATED"

    private const val DEFAULT_GENERATED = "DEFAULT_GENERATED"
}
