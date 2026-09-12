package dev.dmigrate.driver.oracle

/**
 * Welche Spalten einer Oracle-Tabelle **materialisiert** berechnet sind.
 *
 * **Warum das nicht aus dem Data Dictionary kommt.** Gemessen gegen Oracle 23:
 * eine virtuelle Berechnung erkennt man an `USER_TAB_COLS.VIRTUAL_COLUMN = 'YES'`,
 * eine materialisierte aber **nicht** — sie steht dort wie eine gewoehnliche
 * Spalte (`VIRTUAL_COLUMN = 'NO'`, `USER_GENERATED = 'YES'`), und ihr Ausdruck
 * liegt in `DATA_DEFAULT`, genau da, wo bei einer gewoehnlichen Spalte der
 * Default steht. Aus dieser Sicht allein sind die beiden nicht zu trennen.
 *
 * Trennbar sind sie in der DDL, die `DBMS_METADATA.GET_DDL` liefert — dort
 * steht das Wort `MATERIALIZED` an der Spalte. Gebraucht wird daraus nur eine
 * Ja/Nein-Auskunft je Spalte; den **Ausdruck** liefert weiterhin
 * `DATA_DEFAULT`, sauber und ohne Textschneiderei.
 */
internal object OracleGeneratedColumnScanner {

    private const val MATERIALIZED = "MATERIALIZED"
    private const val GENERATED = "GENERATED"

    /** Die Namen der materialisiert berechneten Spalten, unquotiert. */
    fun materializedColumns(ddl: String): Set<String> {
        val body = columnListBody(ddl) ?: return emptySet()
        val result = mutableSetOf<String>()
        for (item in topLevelItems(body)) {
            val name = leadingQuotedName(item) ?: continue
            if (containsKeyword(item, GENERATED) && containsKeyword(item, MATERIALIZED)) result += name
        }
        return result
    }

    /**
     * Der Inhalt der Spaltenliste — zwischen der ersten Klammer und ihrem
     * Partner. Gesucht wird sie **ausserhalb** von Anfuehrungen: ein Tabellen-
     * oder Schemaname darf eine Klammer enthalten, und wer die erste nimmt, die
     * er findet, parst dann den Rest der DDL versetzt.
     */
    private fun columnListBody(ddl: String): String? {
        val open = firstOpenParen(ddl) ?: return null
        val close = matchingParen(ddl, open) ?: return null
        return ddl.substring(open + 1, close)
    }

    private fun firstOpenParen(ddl: String): Int? {
        var i = 0
        while (i < ddl.length) {
            when (ddl[i]) {
                '"', '\'' -> i = skipQuoted(ddl, i)
                '(' -> return i
                else -> i++
            }
        }
        return null
    }

    /** Index der schliessenden Klammer zu der bei [open], oder `null`. */
    private fun matchingParen(text: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < text.length) {
            val c = text[i]
            if (c == '"' || c == '\'') {
                i = skipQuoted(text, i)
                continue
            }
            if (c == '(') depth++
            if (c == ')') {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return null
    }

    /** Index nach der schliessenden Anfuehrung; verdoppelte Zeichen sind Escapes. */
    private fun skipQuoted(text: String, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length) {
            if (text[i] != quote) {
                i++
            } else if (i + 1 < text.length && text[i + 1] == quote) {
                i += 2
            } else {
                return i + 1
            }
        }
        return text.length
    }

    /** Die Glieder der obersten Ebene, an Kommas getrennt. */
    private fun topLevelItems(body: String): List<String> {
        val items = mutableListOf<String>()
        var start = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '"' || c == '\'') {
                i = skipQuoted(body, i)
                continue
            }
            if (c == '(') {
                i = (matchingParen(body, i) ?: (body.length - 1)) + 1
                continue
            }
            if (c == ',') {
                items += body.substring(start, i)
                start = i + 1
            }
            i++
        }
        items += body.substring(start)
        return items.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Der Spaltenname am Anfang eines Glieds — Oracle quotiert ihn immer. */
    private fun leadingQuotedName(item: String): String? {
        if (item.firstOrNull() != '"') return null
        val end = skipQuoted(item, 0)
        return item.substring(1, end - 1).replace("\"\"", "\"").takeIf { it.isNotEmpty() }
    }

    /** [keyword] als ganzes Wort, ausserhalb von Anfuehrungen. */
    private fun containsKeyword(item: String, keyword: String): Boolean {
        var i = 0
        while (i < item.length) {
            val c = item[i]
            if (c == '"' || c == '\'') {
                i = skipQuoted(item, i)
                continue
            }
            if (item.regionMatches(i, keyword, 0, keyword.length, ignoreCase = true)) {
                val before = item.getOrNull(i - 1)
                val after = item.getOrNull(i + keyword.length)
                val boundedBefore = before == null || !(before.isLetterOrDigit() || before == '_')
                val boundedAfter = after == null || !(after.isLetterOrDigit() || after == '_')
                if (boundedBefore && boundedAfter) return true
            }
            i++
        }
        return false
    }
}
