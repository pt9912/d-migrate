package dev.dmigrate.driver.mssql

/**
 * Typisierte Zugriffe auf eine Zeile aus den `sys.*`-Katalogsichten.
 *
 * Der JDBC-Treiber liefert je nach Spalte `Boolean`, `Integer` oder `Short`;
 * die Zugriffe hier falten das auf die Form, die der Reverse erwartet, und
 * scheitern laut, wo eine Pflichtspalte fehlt.
 */
internal fun Map<String, Any?>.string(key: String): String =
    requireNotNull(this[key] as? String) { "missing '$key' in catalog row" }

internal fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Number)?.toInt()

internal fun Map<String, Any?>.long(key: String): Long? = (this[key] as? Number)?.toLong()

internal fun Map<String, Any?>.bool(key: String): Boolean = when (val value = this[key]) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    else -> false
}
