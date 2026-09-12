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

    fun isStored(extra: String): Boolean = extra.contains(STORED, ignoreCase = true)

    private fun isVirtual(extra: String): Boolean = extra.contains(VIRTUAL, ignoreCase = true)

    private const val STORED = "STORED GENERATED"
    private const val VIRTUAL = "VIRTUAL GENERATED"
}
