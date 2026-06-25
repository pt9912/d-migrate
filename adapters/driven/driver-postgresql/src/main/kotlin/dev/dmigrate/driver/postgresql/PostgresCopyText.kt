package dev.dmigrate.driver.postgresql

import java.math.BigDecimal

/**
 * Kodiert Daten-Chunks ins **PostgreSQL-COPY-TEXT-Format** für den COPY-Bulk-Fast-Path
 * (`PostgresTableImportSession`, `import-throughput-copy-path.md`). Reine Funktion (keine
 * DB/Connection) → isoliert unit-testbar; der Encoder ist der korrektheits-kritische Teil
 * (ein Kodierfehler wäre stille Datenkorruption).
 *
 * COPY-TEXT-Vertrag (PostgreSQL): Spalten TAB-getrennt, Zeilen `\n`-terminiert, `\N` = NULL.
 * Escaping **nur** der Trenn-/Escape-Zeichen — Backslash → `\\`, sowie TAB/`\n`/`\r` →
 * `\t`/`\n`/`\r` —, alles andere passiert unverändert. Damit ist ein Literal `\N` als Wert
 * (`\` → `\\`, dann `N` → `N` = `\\N`) eindeutig vom NULL-Marker `\N` unterscheidbar.
 *
 * Nur für COPY-TEXT-sichere Skalartypen aufgerufen (Allowlist in
 * `PostgresTableImportSession.COPY_TEXT_SAFE_JDBC_TYPES`): die kanonische Text-Repräsentation
 * (`toString()`/`BigDecimal.toPlainString()`) ist gültiges PG-Text-Input. json/array/enum/
 * geometry/bytea kommen hier per Vertrag nie an.
 */
internal object PostgresCopyText {

    /** Chunk-Zeilen → COPY-TEXT (jede Zeile = ihre Werte in Spalten-/Bind-Reihenfolge). */
    fun encode(rows: List<Array<Any?>>): String = buildString {
        for (row in rows) {
            for (i in row.indices) {
                if (i > 0) append('\t')
                append(field(row[i]))
            }
            append('\n')
        }
    }

    /** Ein COPY-TEXT-Feld: `\N` für NULL, sonst kanonische Text-Repräsentation + Escaping. */
    fun field(value: Any?): String = when (value) {
        null -> "\\N"
        is String -> escape(value)
        is BigDecimal -> value.toPlainString()      // nie E-Notation
        is Boolean -> if (value) "t" else "f"
        else -> escape(value.toString())
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}
