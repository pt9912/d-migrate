package dev.dmigrate.driver.oracle

/**
 * Ein ggf. schema-qualifizierter Tabellenname für den Datenpfad. Oracle kennt
 * (anders als MSSQL) keine mehrteiligeren Namen als `schema.table` -- Schema
 * ist der User, kein separates "Datenbank"-Konzept ([OracleIdentifiers]).
 */
internal data class OracleQualifiedTableName(
    val schema: String,
    val table: String,
) {
    fun quotedPath(): String = "${OracleIdentifiers.quote(schema)}.${OracleIdentifiers.quote(table)}"

    companion object {
        /** Zerlegt `table` oder `schema.table`; ohne Schema-Praefix gilt [defaultSchema]. */
        fun parse(raw: String, defaultSchema: String): OracleQualifiedTableName {
            val parts = raw.trim().split('.', limit = 2)
            return if (parts.size == 2) {
                OracleQualifiedTableName(parts[0].trim(), parts[1].trim())
            } else {
                OracleQualifiedTableName(defaultSchema, raw.trim())
            }
        }
    }
}
