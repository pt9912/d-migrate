package dev.dmigrate.driver.oracle

/**
 * Zugriff auf eine Katalog-Zeile (`Map<String, Any?>`), geteilt von allen
 * Oracle-Lesepfaden. Lagen zuvor privat in [OracleMetadataQueries]; seit der
 * Partitions-Lesepfad dieselben Spalten liest, gehoeren sie neben die
 * Aufrufer statt in eine davon (Muster: `MssqlCatalogRow`).
 */
internal fun Map<String, Any?>.string(key: String): String =
    requireNotNull(this[key] as? String) { "missing '$key' in catalog row" }

/**
 * Fuer Katalogspalten, die NULL sein duerfen. `ALL_DEPENDENCIES
 * .REFERENCED_OWNER` ist nicht als NOT NULL deklariert; ein einziger
 * NULL-Wert wuerde ueber [string] den ganzen Reverse-Lauf abbrechen
 * statt die Zeile zu ignorieren.
 */
internal fun Map<String, Any?>.stringOrNull(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()
