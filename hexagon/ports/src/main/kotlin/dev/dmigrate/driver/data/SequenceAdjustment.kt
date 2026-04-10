package dev.dmigrate.driver.data

/**
 * Beschreibung einer durchgeführten Sequence-/Identity-Nachführung.
 *
 * @property table Tabellenname
 * @property column Identity-/SERIAL-Spaltenname
 * @property sequenceName PG: expliziter Sequence-Name; MySQL/SQLite: null
 * @property newValue Neuer Sequence-/AUTO_INCREMENT-Wert
 */
data class SequenceAdjustment(
    val table: String,
    val column: String,
    val sequenceName: String?,
    val newValue: Long,
)
