package dev.dmigrate.driver.oracle

/**
 * Trennt Kopf und Rumpf einer gespeicherten PL/SQL-Quelle.
 *
 * `ALL_SOURCE` haelt die Anweisung ohne `CREATE OR REPLACE`, aber sonst
 * vollstaendig: Signatur und Rumpf stehen dort als ein Text. Das neutrale
 * Modell fuehrt beide getrennt — `parameters`, `returns`, `table`, `events`
 * und `timing` als eigene Felder, `body` daneben.
 *
 * Der Schnitt liegt bei Funktionen und Prozeduren **hinter** dem ersten `IS`
 * oder `AS` auf oberster Klammerebene, bei Triggern **vor** dem ersten
 * `DECLARE` oder `BEGIN`. Ein blosses `indexOf` genuegt dafuer nicht: PL/SQL
 * traegt diese Woerter in Zeichenketten, in Kommentaren, in quotierten
 * Bezeichnern und — beim Trigger — in der `REFERENCING NEW AS NEW`-Klausel.
 * Der Scanner ueberspringt diese Bereiche und zaehlt die Klammertiefe mit.
 *
 * Findet er kein Schluesselwort, gibt er `null` zurueck; der Aufrufer meldet
 * das, statt einen falschen Rumpf abzulegen.
 */
internal object OracleRoutineBody {

    /** Kopf und Rumpf einer Quelle, beide ohne umgebenden Leerraum. */
    data class Split(val header: String, val body: String)

    /**
     * Schnitt hinter dem einleitenden `IS`/`AS`. Beide Woerter sind in der
     * Routinen-Deklaration gleichbedeutend.
     */
    fun splitRoutine(source: String): Split? = split(source, ROUTINE_KEYWORDS, keepKeyword = false)

    /**
     * Schnitt vor dem `DECLARE`/`BEGIN` des PL/SQL-Blocks. Anders als bei der
     * Routine gehoert das Schluesselwort zum Rumpf: der Block beginnt damit.
     */
    fun splitTrigger(source: String): Split? = split(source, TRIGGER_KEYWORDS, keepKeyword = true)

    private val ROUTINE_KEYWORDS = listOf("IS", "AS")
    private val TRIGGER_KEYWORDS = listOf("DECLARE", "BEGIN")

    private fun split(sql: String, keywords: List<String>, keepKeyword: Boolean): Split? {
        val (keyword, at) = firstTopLevelKeyword(sql, keywords) ?: return null
        val body = sql.substring(if (keepKeyword) at else at + keyword.length).trim()
        if (body.isEmpty()) return null
        return Split(header = sql.substring(0, at).trim(), body = body)
    }

    /** Das erste der [keywords] auf oberster Klammerebene, mit seinem Startindex. */
    private fun firstTopLevelKeyword(sql: String, keywords: List<String>): Pair<String, Int>? {
        var i = 0
        var depth = 0
        while (i < sql.length) {
            val skipped = skipNonCode(sql, i)
            if (skipped > i) {
                i = skipped
                continue
            }
            when {
                sql[i] == '(' -> depth++
                sql[i] == ')' -> depth--
                depth == 0 -> keywords.firstOrNull { isKeywordAt(sql, i, it) }?.let { return it to i }
            }
            i++
        }
        return null
    }

    /**
     * Das Ende des Kommentars, Literals oder quotierten Bezeichners an [i],
     * oder [i] selbst, wenn dort keins beginnt.
     */
    private fun skipNonCode(sql: String, i: Int): Int = when {
        sql.startsWith("--", i) -> sql.indexOf('\n', i).takeIf { it >= 0 }?.plus(1) ?: sql.length
        sql.startsWith("/*", i) -> sql.indexOf("*/", i).takeIf { it >= 0 }?.plus(2) ?: sql.length
        isAlternativeQuoteStart(sql, i) -> skipAlternativeQuote(sql, i)
        sql[i] == '\'' -> skipQuoted(sql, i, '\'')
        sql[i] == '"' -> skipQuoted(sql, i, '"')
        else -> i
    }

    /**
     * Beginnt an [i] Oracles alternatives Quoting (`q'!…!'`)?
     *
     * Nur wenn das `q` selbst kein Teil eines Bezeichners ist — sonst faenge
     * der Scanner in `seq'x'` mitten im Namen an zu quoten.
     */
    private fun isAlternativeQuoteStart(sql: String, i: Int): Boolean {
        if (sql[i] != 'q' && sql[i] != 'Q') return false
        if (sql.getOrNull(i + 1) != '\'') return false
        val before = if (i == 0) ' ' else sql[i - 1]
        return !before.isLetterOrDigit() && before != '_'
    }

    /**
     * Ueberspringt `q'X…X'`. Klammernde Zeichen spiegeln sich am Ende
     * (`q'[…]'`), jedes andere Zeichen steht auf beiden Seiten gleich.
     */
    private fun skipAlternativeQuote(sql: String, start: Int): Int {
        val opening = sql.getOrNull(start + 2) ?: return sql.length
        val closing = when (opening) {
            '[' -> ']'
            '{' -> '}'
            '<' -> '>'
            '(' -> ')'
            else -> opening
        }
        var i = start + 3
        while (i < sql.length - 1) {
            if (sql[i] == closing && sql[i + 1] == '\'') return i + 2
            i++
        }
        return sql.length
    }

    /** Ueberspringt ein Literal bzw. einen quotierten Bezeichner samt Verdopplungs-Escape. */
    private fun skipQuoted(sql: String, start: Int, closing: Char): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == closing) {
                // `''` bzw. `""` ist das entwertete Zeichen, kein Ende.
                if (sql.getOrNull(i + 1) == closing) i += 2 else return i + 1
            } else {
                i++
            }
        }
        return sql.length
    }

    private fun isKeywordAt(sql: String, i: Int, keyword: String): Boolean {
        val end = i + keyword.length
        if (end > sql.length) return false
        if (!sql.regionMatches(i, keyword, 0, keyword.length, ignoreCase = true)) return false
        val before = if (i == 0) ' ' else sql[i - 1]
        val after = if (end >= sql.length) ' ' else sql[end]
        return !before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_'
    }
}
