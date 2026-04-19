package dev.dmigrate.driver.data

/**
 * Beschreibung einer bereits durchgeführten Generator-/Sequence-Nachführung
 * für den Import-Report.
 *
 * @property table Logischer Tabellenname des Imports
 * @property column Generator-/Identity-Spaltenname
 * @property sequenceName PostgreSQL: expliziter Sequence-Name; MySQL/SQLite: null
 * @property newValue Nächster von der Datenbank ohne expliziten Generatorwert
 *   auszugebender Wert. Beschreibt bewusst die Report-Semantik und nicht den
 *   internen Zustand einzelner DB-Mechanismen wie `setval(..., is_called)` oder
 *   `sqlite_sequence.seq`.
 */
data class SequenceAdjustment(
    val table: String,
    val column: String,
    val sequenceName: String?,
    val newValue: Long,
)
