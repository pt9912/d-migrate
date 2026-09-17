package dev.dmigrate.driver.sqlite

/**
 * Shared quote-/paren-aware low-level scanning helpers for walking a SQLite
 * CREATE-TABLE statement (`sqlite_master.sql`). Used by
 * [SqliteCheckConstraintScanner], [SqliteUniqueConstraintScanner] und
 * [SqliteForeignKeyConstraintScanner] und [SqliteGeneratedColumnScanner] —
 * the lexing rules (doubled-quote escapes, `[...]` identifiers, balanced
 * parens, comments) must stay identical between the scanners, so they live
 * once here.
 *
 * **Das gilt auch fuer das Zerlegen selbst.** Der Rumpf einer
 * `CREATE TABLE`-Anweisung ([tableBody]) und seine Glieder auf oberster Ebene
 * ([topLevelItems]) standen einmal in zwei Scannern — und bereits
 * verschieden; ein Defekt sass in der einen Kopie und nicht in der anderen.
 *
 * **Kommentare gehoeren dazu.** SQLite speichert den `CREATE TABLE`-Text
 * wortgetreu, samt Zeilen- und Blockkommentaren. Ein Scanner, der sie nicht
 * kennt, liest ein `CHECK` oder ein `AUTOINCREMENT` aus einem Kommentar — und
 * ein Apostroph darin verschiebt ihm das Ueberspringen von Literalen, sodass
 * er den Rest der Anweisung falsch liest.
 */
internal object SqliteDdlScanning {

    /** `CONSTRAINT <name>` (quoted or bare) directly before a keyword. */
    private val CONSTRAINT_NAME_BEFORE = Regex(
        """CONSTRAINT\s+("(?:[^"]|"")+"|`(?:[^`]|``)+`|\[[^\]]+]|\S+)\s+$""",
        RegexOption.IGNORE_CASE,
    )

    /** True when [keyword] starts at [i] as a whole word. */
    fun isKeywordAt(sql: String, i: Int, keyword: String): Boolean {
        if (!sql.regionMatches(i, keyword, 0, keyword.length, ignoreCase = true)) return false
        val before = sql.getOrNull(i - 1)
        val after = sql.getOrNull(i + keyword.length)
        return (before == null || !before.isIdentifierChar()) &&
            (after == null || !after.isIdentifierChar())
    }

    fun Char.isIdentifierChar(): Boolean = isLetterOrDigit() || this == '_'

    /**
     * Ob [keyword] als ganzes Wort im **Code** steht — nicht in einem Literal,
     * einem quotierten Bezeichner oder einem Kommentar. Ein `contains` traf
     * sonst ein `AUTOINCREMENT` in einem Kommentar oder in einem Spaltennamen.
     * Mehrwortige Schluesselwoerter (`WITHOUT ROWID`) trennt beliebiger
     * Leerraum.
     */
    fun containsKeyword(sql: String, keyword: String): Boolean {
        val words = keyword.split(" ")
        var index = 0
        while (index < sql.length) {
            val afterComment = skipComment(sql, index)
            if (afterComment > index) {
                index = afterComment
                continue
            }
            index = when (sql[index]) {
                '\'', '"', '`' -> skipQuoted(sql, index)
                '[' -> skipBracketIdentifier(sql, index)
                else -> {
                    if (wordsAt(sql, index, words)) return true
                    index + 1
                }
            }
        }
        return false
    }

    private fun wordsAt(sql: String, start: Int, words: List<String>): Boolean {
        var index = start
        for ((position, word) in words.withIndex()) {
            if (position > 0) {
                val before = index
                while (index < sql.length && sql[index].isWhitespace()) index++
                if (index == before) return false
            }
            if (!isKeywordAt(sql, index, word)) return false
            index += word.length
        }
        return true
    }

    /** The (unquoted) name when the text before [keywordStart] ends in
     *  `CONSTRAINT <name>`, else null (unnamed clause). */
    fun constraintNameBefore(sql: String, keywordStart: Int): String? {
        val match = CONSTRAINT_NAME_BEFORE.find(sql.substring(0, keywordStart)) ?: return null
        return unquoteIdentifier(match.groupValues[1])
    }

    fun unquoteIdentifier(raw: String): String {
        val t = raw.trim()
        return when {
            t.length >= 2 && t.first() == '"' && t.last() == '"' ->
                t.substring(1, t.length - 1).replace("\"\"", "\"")
            t.length >= 2 && t.first() == '`' && t.last() == '`' ->
                t.substring(1, t.length - 1).replace("``", "`")
            t.length >= 2 && t.first() == '[' && t.last() == ']' -> t.substring(1, t.length - 1)
            else -> t
        }
    }

    /** Index just past the closing quote of the literal starting at [start]
     *  (quote char = `sql[start]`), honouring doubled-quote escapes;
     *  end-of-string for an unterminated literal. */
    fun skipQuoted(sql: String, start: Int): Int {
        val q = sql[start]
        var j = start + 1
        while (j < sql.length) {
            if (sql[j] != q) {
                j++
            } else if (j + 1 < sql.length && sql[j + 1] == q) {
                j += 2
            } else {
                return j + 1
            }
        }
        return sql.length
    }

    /**
     * Index hinter einem Kommentar, der bei [start] beginnt — oder [start],
     * wenn dort keiner beginnt. Ein Zeilenkommentar reicht bis zum
     * Zeilenende (oder bis zum Ende des Textes), ein Blockkommentar bis zu
     * seinem Schlusszeichen; ein nicht geschlossener bis zum Ende.
     */
    fun skipComment(sql: String, start: Int): Int = when {
        sql.startsWith("--", start) ->
            sql.indexOf('\n', start).let { if (it < 0) sql.length else it + 1 }
        sql.startsWith("/*", start) ->
            sql.indexOf("*/", start + 2).let { if (it < 0) sql.length else it + 2 }
        else -> start
    }

    /**
     * Der naechste Index, an dem **Code** steht: ueberspringt Kommentare ab
     * [start], auch mehrere hintereinander.
     */
    fun skipComments(sql: String, start: Int): Int {
        var index = start
        while (true) {
            val next = skipComment(sql, index)
            if (next == index) return index
            index = next
        }
    }

    fun skipBracketIdentifier(sql: String, start: Int): Int {
        val close = sql.indexOf(']', start + 1)
        return if (close < 0) sql.length else close + 1
    }

    /** Index of the parenthesis balancing the one just before [start], or
     *  null when the DDL never balances (malformed → caller skips). */
    fun matchingParenEnd(sql: String, start: Int): Int? {
        var depth = 1
        var i = start
        while (i < sql.length) {
            val afterComment = skipComment(sql, i)
            if (afterComment > i) {
                i = afterComment
                continue
            }
            when (sql[i]) {
                '(' -> {
                    depth++
                    i++
                }
                ')' -> {
                    depth--
                    if (depth == 0) return i
                    i++
                }
                '\'', '"', '`' -> i = skipQuoted(sql, i)
                '[' -> i = skipBracketIdentifier(sql, i)
                else -> i++
            }
        }
        return null
    }

    /**
     * Der Rumpf eines `CREATE TABLE`-Textes: der Inhalt zwischen der ersten
     * Klammer, die im **Code** steht (nicht in einem Literal, einem quotierten
     * Bezeichner oder einem Kommentar), und ihrem Partner. `null`, wenn es
     * keine gibt oder sie nicht schliesst.
     */
    fun tableBody(createSql: String): String? {
        var index = 0
        while (index < createSql.length) {
            val afterComment = skipComment(createSql, index)
            if (afterComment > index) {
                index = afterComment
                continue
            }
            index = when (createSql[index]) {
                '\'', '"', '`' -> skipQuoted(createSql, index)
                '[' -> skipBracketIdentifier(createSql, index)
                '(' -> {
                    val end = matchingParenEnd(createSql, index + 1) ?: return null
                    return createSql.substring(index + 1, end)
                }
                else -> index + 1
            }
        }
        return null
    }

    /**
     * Die Glieder eines Rumpfes auf **oberster** Ebene, an Kommas getrennt und
     * getrimmt — je Glied entweder eine Spaltendefinition oder eine Klausel
     * auf Tabellenebene. Klammergruppen, Literale, quotierte Bezeichner und
     * Kommentare zaehlen nicht mit.
     */
    fun topLevelItems(body: String): List<String> {
        val items = mutableListOf<String>()
        var start = 0
        var index = 0
        while (index < body.length) {
            val afterComment = skipComment(body, index)
            if (afterComment > index) {
                index = afterComment
                continue
            }
            when (body[index]) {
                '\'', '"', '`' -> { index = skipQuoted(body, index); continue }
                '[' -> { index = skipBracketIdentifier(body, index); continue }
                '(' -> { index = matchingParenEnd(body, index + 1)?.plus(1) ?: body.length; continue }
                ',' -> {
                    items += body.substring(start, index)
                    start = index + 1
                }
            }
            index++
        }
        items += body.substring(start)
        return items.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Das Ende des Schluesselwort-Laufs [keyword] ab [start] — der Index hinter
     * dem letzten Wort —, oder `null`, wenn dort keiner steht. Mehrwortige
     * Schluesselwoerter (`FOREIGN KEY`) trennt **beliebiger** Leerraum; ihre
     * Laenge im Text ist deshalb nicht `keyword.length`.
     */
    fun keywordRunEnd(sql: String, start: Int, keyword: String): Int? {
        var index = start
        for ((position, word) in keyword.split(" ").withIndex()) {
            if (position > 0) {
                val before = index
                while (index < sql.length && sql[index].isWhitespace()) index++
                if (index == before) return null
            }
            if (!isKeywordAt(sql, index, word)) return null
            index += word.length
        }
        return index
    }

    /** For a keyword at [keywordStart] of length [keywordLength]: the index of
     *  the balancing `)` of the paren group that follows (after whitespace),
     *  or null when no `(` follows or the parens never balance. */
    fun parenGroupEnd(sql: String, keywordStart: Int, keywordLength: Int): Int? {
        var j = keywordStart + keywordLength
        while (j < sql.length) {
            val afterComment = skipComments(sql, j)
            if (afterComment > j) {
                j = afterComment
                continue
            }
            if (!sql[j].isWhitespace()) break
            j++
        }
        if (j >= sql.length || sql[j] != '(') return null
        return matchingParenEnd(sql, j + 1)
    }
}
