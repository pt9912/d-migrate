package dev.dmigrate.driver.sqlite

/**
 * Quote-aware scanner for `CHECK (...)` clauses in a SQLite CREATE TABLE
 * statement. SQLite stores the full DDL in `sqlite_master.sql` — CHECK
 * constraints can only be recovered from there.
 *
 * A regex cannot balance parentheses — the previous
 * `CHECK\s*\((.+?)\)` truncated every expression containing an inner
 * `)` (i.e. any `IN (...)` list) at the first closing paren. The scan
 * walks the DDL once, skips `'…'`/`"…"`/`` `…` `` literals (with
 * doubled-quote escapes) and `[…]` identifiers, and captures each
 * expression up to its balancing parenthesis. A never-balancing
 * (malformed) clause is skipped instead of looping or truncating.
 *
 * Extracted from [SqliteTypeMapping] as its own object (real split, no
 * suppression) — the scanner is a cohesive unit with its own helpers.
 */
internal object SqliteCheckConstraintScanner {

    /**
     * Scan result: named CHECK constraints as (name, expression) pairs
     * plus the expressions of unnamed CHECK clauses (column- or
     * table-level `CHECK (...)` without a `CONSTRAINT <name>` prefix).
     * Unnamed checks cannot become
     * [dev.dmigrate.core.model.ConstraintDefinition]s (the model keys
     * constraints by name) — the reader surfaces them as R203 notes
     * instead of dropping them silently.
     */
    data class Scan(
        val named: List<Pair<String, String>>,
        val unnamedExpressions: List<String>,
    )

    private const val CHECK_KEYWORD = "CHECK"

    /** `CONSTRAINT <name>` (quoted or bare) directly before a CHECK keyword. */
    private val CONSTRAINT_NAME_BEFORE = Regex(
        """CONSTRAINT\s+("(?:[^"]|"")+"|`(?:[^`]|``)+`|\[[^\]]+]|\S+)\s+$""",
        RegexOption.IGNORE_CASE,
    )

    fun scan(createSql: String): Scan {
        val named = mutableListOf<Pair<String, String>>()
        val unnamed = mutableListOf<String>()
        var i = 0
        while (i < createSql.length) {
            when (createSql[i]) {
                '\'', '"', '`' -> i = skipQuoted(createSql, i)
                '[' -> i = skipBracketIdentifier(createSql, i)
                else -> {
                    val end = if (isCheckKeywordAt(createSql, i)) checkExpressionEnd(createSql, i) else null
                    if (end != null) {
                        val open = createSql.indexOf('(', i)
                        val expr = createSql.substring(open + 1, end).trim()
                        val name = constraintNameBefore(createSql, i)
                        if (name != null) named += name to expr else unnamed += expr
                        i = end + 1
                    } else {
                        i++
                    }
                }
            }
        }
        return Scan(named, unnamed)
    }

    private fun isCheckKeywordAt(sql: String, i: Int): Boolean {
        if (!sql.regionMatches(i, CHECK_KEYWORD, 0, CHECK_KEYWORD.length, ignoreCase = true)) return false
        val before = sql.getOrNull(i - 1)
        val after = sql.getOrNull(i + CHECK_KEYWORD.length)
        return (before == null || !before.isIdentifierChar()) &&
            (after == null || !after.isIdentifierChar())
    }

    private fun Char.isIdentifierChar(): Boolean = isLetterOrDigit() || this == '_'

    /**
     * For a CHECK keyword at [checkStart]: the index of the expression's
     * balancing `)`, or null when no `(` follows or the parens never
     * balance.
     */
    private fun checkExpressionEnd(sql: String, checkStart: Int): Int? {
        var j = checkStart + CHECK_KEYWORD.length
        while (j < sql.length && sql[j].isWhitespace()) j++
        if (j >= sql.length || sql[j] != '(') return null
        return matchingParenEnd(sql, j + 1)
    }

    /** The (unquoted) name when the text before the CHECK keyword at
     *  [checkStart] ends in `CONSTRAINT <name>`, else null (unnamed). */
    private fun constraintNameBefore(sql: String, checkStart: Int): String? {
        val match = CONSTRAINT_NAME_BEFORE.find(sql.substring(0, checkStart)) ?: return null
        return unquoteIdentifier(match.groupValues[1])
    }

    private fun unquoteIdentifier(raw: String): String {
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
    private fun skipQuoted(sql: String, start: Int): Int {
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

    private fun skipBracketIdentifier(sql: String, start: Int): Int {
        val close = sql.indexOf(']', start + 1)
        return if (close < 0) sql.length else close + 1
    }

    /** Index of the parenthesis balancing the one just before [start], or
     *  null when the DDL never balances (malformed → caller skips). */
    private fun matchingParenEnd(sql: String, start: Int): Int? {
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
}
