package dev.dmigrate.driver.sqlite

/**
 * Schneidet die deklarierten Schluessel aus einer `CREATE INDEX`-Anweisung.
 *
 * SQLite fuehrt den Text eines Ausdrucks-Schluessels **nirgends im Katalog**:
 * `PRAGMA index_xinfo` meldet fuer eine Ausdrucksposition `cid = -2` und
 * `name = NULL`, und anders als PostgreSQL (`pg_get_indexdef`), Oracle
 * (`ALL_IND_EXPRESSIONS`) und MySQL (`information_schema.statistics`) gibt es
 * keine zweite Quelle. Uebrig bleibt der ursprungliche Anweisungstext in
 * `sqlite_master.sql` — den der Reverse ohnehin schon liest, um die
 * `WHERE`-Klausel zu gewinnen.
 *
 * Der Schnitt ist deshalb positionsweise: die `key = 1`-Zeilen von
 * `index_xinfo` stehen in Deklarationsreihenfolge, und der i-te Schluessel im
 * Text gehoert zur i-ten Zeile. Wo der Katalog einen Namen liefert, gilt der
 * Name; wo er schweigt, tritt der Text ein.
 *
 * Findet der Scanner keine ausgewogene Schluesselliste, gibt er `null`
 * zurueck — der Aufrufer meldet das, statt zu raten.
 */
internal object SqliteIndexKeyScanner {

    /**
     * Die Schluessel in Deklarationsreihenfolge, ohne `ASC`/`DESC` und ohne
     * `COLLATE <name>`: beides steht im Modell als eigenes Feld, im Text
     * mitzunehmen ergaebe einen Ausdruck, den kein Zieldialekt kennt.
     */
    fun keysOf(createSql: String): List<String>? {
        val open = topLevelOpenParen(createSql) ?: return null
        val close = matchingCloseParen(createSql, open) ?: return null
        return splitTopLevel(createSql.substring(open + 1, close))
            .map { stripKeyModifiers(it) }
            .takeIf { keys -> keys.isNotEmpty() && keys.all { it.isNotBlank() } }
    }

    /** Die erste Klammer, die nicht in einer Zeichenkette oder einem Bezeichner steht. */
    private fun topLevelOpenParen(sql: String): Int? {
        var i = 0
        while (i < sql.length) {
            val skipped = skipNonCode(sql, i)
            if (skipped > i) {
                i = skipped
                continue
            }
            if (sql[i] == '(') return i
            i++
        }
        return null
    }

    private fun matchingCloseParen(sql: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < sql.length) {
            val skipped = skipNonCode(sql, i)
            if (skipped > i) {
                i = skipped
                continue
            }
            when (sql[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return null
    }

    private fun splitTopLevel(keyList: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var i = 0
        while (i < keyList.length) {
            val skipped = skipNonCode(keyList, i)
            if (skipped > i) {
                current.append(keyList, i, skipped)
                i = skipped
                continue
            }
            val ch = keyList[i]
            when {
                ch == '(' -> { depth++; current.append(ch) }
                ch == ')' -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> { parts += current.toString().trim(); current.clear() }
                else -> current.append(ch)
            }
            i++
        }
        parts += current.toString().trim()
        return parts.filter { it.isNotEmpty() }
    }

    /**
     * `COLLATE` und die Sortierrichtung stehen hinter dem Schluessel und
     * gehoeren nicht zu ihm. Beides wird von hinten abgetragen, damit ein
     * `LOWER(nm) COLLATE NOCASE DESC` auf `LOWER(nm)` endet.
     */
    private fun stripKeyModifiers(key: String): String {
        var rest = key.trim()
        while (true) {
            val trimmed = rest.trimEnd()
            val lower = trimmed.lowercase()
            val next = when {
                lower.endsWith(" asc") -> trimmed.dropLast(4)
                lower.endsWith(" desc") -> trimmed.dropLast(5)
                else -> COLLATE_SUFFIX.find(trimmed)?.let { trimmed.substring(0, it.range.first) }
            } ?: return trimmed
            rest = next
        }
    }

    /** Zeichenketten, geklammerte Bezeichner und Kommentare, die kein Code sind. */
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
            '`' -> skipDelimited(sql, index, '`')
            '[' -> sql.indexOf(']', index).let { if (it < 0) sql.length else it + 1 }
            else -> index
        }
    }

    /** SQLite verdoppelt das Begrenzungszeichen, um es einzubetten. */
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

    private val COLLATE_SUFFIX = Regex("""\sCOLLATE\s+("[^"]*"|\[[^\]]*]|`[^`]*`|\w+)\s*$""", RegexOption.IGNORE_CASE)
}
