package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * Ein ggf. schema-qualifizierter Tabellenname für den Datenpfad. Ohne
 * Schema-Präfix gilt das Default-Schema der Verbindung (üblicherweise `dbo`).
 */
internal data class MssqlQualifiedTableName(
    val schema: String,
    val table: String,
    /** Datenbank-Teil eines dreiteiligen Namens; `null` = Datenbank der Verbindung. */
    val database: String? = null,
    /** Verbindungsserver-Teil eines vierteiligen Namens; `null` = lokaler Server. */
    val server: String? = null,
) {

    /**
     * `[server].[db].[schema].[table]` für SQL-Interpolation (führende Teile nur,
     * wenn benannt) — dieselbe Form, die der Lesepfad
     * (`AbstractJdbcDataReader.quoteTablePath`) rendert.
     */
    fun quotedPath(): String = buildString {
        server?.let { append(MssqlIdentifiers.bracket(it)).append('.') }
        database?.let { append(MssqlIdentifiers.bracket(it)).append('.') }
        append(MssqlIdentifiers.qualified(schema, table))
    }

    companion object {
        /**
         * Zerlegt `table` / `schema.table` / `[my schema].[my table]`. Punkte in
         * Klammern trennen nicht; `]]` ist das Escape für `]`.
         */
        fun parse(raw: String, defaultSchema: String): MssqlQualifiedTableName {
            val segments = splitUnbracketed(raw.trim())
            return when (segments.size) {
                0 -> MssqlQualifiedTableName(defaultSchema, raw.trim())
                1 -> MssqlQualifiedTableName(defaultSchema, segments[0])
                2 -> MssqlQualifiedTableName(segments[0], segments[1])
                // Drei-/vierteilig (`db.schema.table`, `server.db.schema.table`):
                // führende Teile bleiben erhalten — der Lesepfad
                // (AbstractJdbcDataReader.quoteTablePath) rendert sie ebenfalls,
                // sonst zielten Lesen und Schreiben auseinander.
                else -> MssqlQualifiedTableName(
                    schema = segments[segments.size - 2],
                    table = segments.last(),
                    database = segments[segments.size - 3],
                    server = segments.getOrNull(segments.size - 4),
                )
            }
        }

        /** Default-Schema der Verbindung (`SCHEMA_NAME()`), Fallback `dbo`. */
        fun defaultSchema(session: JdbcOperations): String = MssqlIdentifiers.currentSchema(session)

        private fun splitUnbracketed(text: String): List<String> {
            val segments = mutableListOf(StringBuilder())
            var inBrackets = false
            var index = 0
            while (index < text.length) {
                val ch = text[index]
                when {
                    inBrackets && ch == ']' ->
                        if (index + 1 < text.length && text[index + 1] == ']') {
                            segments.last().append(']')
                            index++
                        } else {
                            inBrackets = false
                        }
                    !inBrackets && ch == '[' -> inBrackets = true
                    !inBrackets && ch == '.' -> segments += StringBuilder()
                    else -> segments.last().append(ch)
                }
                index++
            }
            return segments.map { it.toString().trim() }.filter { it.isNotEmpty() }
        }
    }
}
