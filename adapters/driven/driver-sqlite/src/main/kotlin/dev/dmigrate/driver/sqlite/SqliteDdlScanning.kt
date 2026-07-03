package dev.dmigrate.driver.sqlite

/**
 * Shared quote-/paren-aware low-level scanning helpers for walking a SQLite
 * CREATE-TABLE statement (`sqlite_master.sql`). Used by
 * [SqliteCheckConstraintScanner] and [SqliteUniqueConstraintScanner] — the
 * lexing rules (doubled-quote escapes, `[...]` identifiers, balanced parens)
 * must stay identical between the scanners, so they live once here.
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

    /** For a keyword at [keywordStart] of length [keywordLength]: the index of
     *  the balancing `)` of the paren group that follows (after whitespace),
     *  or null when no `(` follows or the parens never balance. */
    fun parenGroupEnd(sql: String, keywordStart: Int, keywordLength: Int): Int? {
        var j = keywordStart + keywordLength
        while (j < sql.length && sql[j].isWhitespace()) j++
        if (j >= sql.length || sql[j] != '(') return null
        return matchingParenEnd(sql, j + 1)
    }
}
