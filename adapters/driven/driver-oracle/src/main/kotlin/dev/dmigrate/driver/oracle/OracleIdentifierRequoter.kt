package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.SchemaDefinition

/**
 * Setzt in rohem SQL-Text (CHECK-Ausdruck, Sichten-Rumpf) die Bezeichner in
 * Anfuehrungszeichen, die das Schema kennt.
 *
 * **Warum nur Oracle.** d-migrate legt Tabellen und Spalten wortgetreu
 * gequotet an, also klein geschrieben. Oracle faltet einen *unquoted*
 * Bezeichner dagegen auf GROSSSCHREIBUNG — als einzige der fuenf
 * Ziel-Engines. Ein `CHECK (total_amount >= 0)` sucht dort also nach
 * `TOTAL_AMOUNT` und findet die Spalte `"total_amount"` nicht:
 * `ORA-00904: "TOTAL_AMOUNT": invalid identifier` (live gemessen). Die
 * uebrigen vier Dialekte vertragen die unquotierte Schreibweise zufaellig —
 * PostgreSQL faltet in dieselbe Richtung wie das Modell, SQL Server
 * vergleicht per Default ohne Ruecksicht auf Gross-/Kleinschreibung.
 *
 * **Was er anfasst und was nicht.** Nur ein Wort, das zeichenweise (ohne
 * Ruecksicht auf Gross-/Kleinschreibung) einem Tabellen- oder Spaltennamen
 * des Schemas entspricht. Alles andere bleibt, wie es steht:
 *
 * - Zeichenketten, bereits gequotete Bezeichner und Kommentare werden
 *   uebersprungen.
 * - Ein Wort, dem eine offene Klammer folgt, ist ein Funktionsaufruf.
 * - Ein Wort aus [RESERVED] bleibt unangetastet, auch wenn eine Spalte so
 *   heisst — an dieser Stelle im Satz ist es das Schluesselwort.
 *
 * Das ist bewusst kein Parser: was der Requoter nicht sicher erkennt, laesst
 * er in Ruhe. Roher Text ist im neutralen Modell die Aussage des Eigentuemers
 * (siehe `spec/neutral-model-spec.md`), und ein falsch gesetztes
 * Anfuehrungszeichen waere schlimmer als ein fehlendes.
 */
internal object OracleIdentifierRequoter {

    /**
     * Die Bezeichner, die ein Schema kennt: Tabellennamen und die Spalten
     * aller Tabellen, jeweils klein geschrieben auf ihre wirkliche
     * Schreibweise abgebildet.
     *
     * Kollidieren zwei Schreibweisen (`Status` und `status`), bleibt die
     * erste stehen — den Fall zu raten waere schlimmer als ihn zu lassen.
     */
    fun knownIdentifiers(schema: SchemaDefinition): Map<String, String> {
        val known = LinkedHashMap<String, String>()
        for ((tableName, table) in schema.tables) {
            known.putIfAbsent(tableName.lowercase(), tableName)
            for (columnName in table.columns.keys) known.putIfAbsent(columnName.lowercase(), columnName)
        }
        return known
    }

    fun requote(sql: String, known: Map<String, String>, quote: (String) -> String): String {
        if (known.isEmpty()) return sql
        val out = StringBuilder(sql.length)
        var index = 0
        while (index < sql.length) {
            val skipped = skipNonCode(sql, index)
            if (skipped > index) {
                out.append(sql, index, skipped)
                index = skipped
                continue
            }
            val char = sql[index]
            if (!char.isLetter() && char != '_') {
                out.append(char)
                index++
                continue
            }
            val end = wordEnd(sql, index)
            val word = sql.substring(index, end)
            out.append(replacementFor(word, sql, end, known, quote))
            index = end
        }
        return out.toString()
    }

    private fun replacementFor(
        word: String,
        sql: String,
        end: Int,
        known: Map<String, String>,
        quote: (String) -> String,
    ): String {
        val identifier = known[word.lowercase()] ?: return word
        if (word.uppercase() in RESERVED) return word
        if (isFunctionCall(sql, end)) return word
        return quote(identifier)
    }

    private fun wordEnd(sql: String, start: Int): Int {
        var i = start
        while (i < sql.length && sql[i].isIdentifierChar()) i++
        return i
    }

    /** Oracle laesst in einem Bezeichner ausserdem `_`, `$` und `#` zu. */
    private fun Char.isIdentifierChar(): Boolean = isLetterOrDigit() || this in "_$#"

    /** Ein Wort, dem — ueber Leerraum hinweg — eine offene Klammer folgt. */
    private fun isFunctionCall(sql: String, wordEnd: Int): Boolean {
        var i = wordEnd
        while (i < sql.length && sql[i].isWhitespace()) i++
        return i < sql.length && sql[i] == '('
    }

    /** Zeichenketten, gequotete Bezeichner und Kommentare. */
    @Suppress("ReturnCount")
    private fun skipNonCode(sql: String, index: Int): Int {
        when {
            sql.startsWith("--", index) ->
                return sql.indexOf('\n', index).let { if (it < 0) sql.length else it }
            sql.startsWith("/*", index) ->
                return sql.indexOf("*/", index).let { if (it < 0) sql.length else it + 2 }
        }
        return when (sql[index]) {
            '\'' -> skipDelimited(sql, index, '\'')
            '"' -> skipDelimited(sql, index, '"')
            else -> index
        }
    }

    private fun skipDelimited(sql: String, start: Int, delimiter: Char): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == delimiter) {
                if (i + 1 < sql.length && sql[i + 1] == delimiter) i += 2 else return i + 1
            } else {
                i++
            }
        }
        return sql.length
    }

    /**
     * Woerter, die im Satz eine Rolle haben, auch wenn eine Spalte so heisst.
     * Bewusst knapp gehalten: die Liste soll die Faelle abdecken, die in
     * einem CHECK-Ausdruck oder Sichten-Rumpf wirklich vorkommen, nicht
     * Oracles vollstaendiges Vokabular nachbilden.
     */
    private val RESERVED = setOf(
        "AND", "AS", "ASC", "BETWEEN", "BY", "CASE", "CROSS", "DESC", "DISTINCT", "ELSE", "END",
        "ESCAPE", "EXISTS", "FALSE", "FROM", "FULL", "GROUP", "HAVING", "IN", "INNER", "IS",
        "JOIN", "LEFT", "LEVEL", "LIKE", "NOT", "NULL", "ON", "OR", "ORDER", "OUTER", "RIGHT",
        "ROWNUM", "SELECT", "THEN", "TRUE", "UNION", "USING", "WHEN", "WHERE", "WITH",
    )
}
