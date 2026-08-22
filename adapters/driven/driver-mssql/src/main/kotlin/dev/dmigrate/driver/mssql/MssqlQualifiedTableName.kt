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
) {

    /** `[db].[schema].[table]` für SQL-Interpolation (Datenbank nur wenn benannt). */
    fun quotedPath(): String = buildString {
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
                // Dreiteilig `db.schema.table`: die Datenbank bleibt erhalten —
                // der Lesepfad (AbstractJdbcDataReader.quoteTablePath) rendert
                // sie ebenfalls, sonst zielten Lesen und Schreiben auseinander.
                else -> MssqlQualifiedTableName(
                    schema = segments[segments.size - 2],
                    table = segments.last(),
                    database = segments[segments.size - 3],
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
