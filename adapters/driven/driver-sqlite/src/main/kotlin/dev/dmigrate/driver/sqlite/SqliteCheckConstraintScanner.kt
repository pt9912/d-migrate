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
 * suppression); the low-level quote-/paren-lexing lives in the shared
 * [SqliteDdlScanning] (also used by [SqliteUniqueConstraintScanner]).
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

    fun scan(createSql: String): Scan {
        val named = mutableListOf<Pair<String, String>>()
        val unnamed = mutableListOf<String>()
        var i = 0
        while (i < createSql.length) {
            when (createSql[i]) {
                '\'', '"', '`' -> i = SqliteDdlScanning.skipQuoted(createSql, i)
                '[' -> i = SqliteDdlScanning.skipBracketIdentifier(createSql, i)
                else -> {
                    val end = if (SqliteDdlScanning.isKeywordAt(createSql, i, CHECK_KEYWORD)) {
                        SqliteDdlScanning.parenGroupEnd(createSql, i, CHECK_KEYWORD.length)
                    } else {
                        null
                    }
                    if (end != null) {
                        val open = createSql.indexOf('(', i)
                        val expr = createSql.substring(open + 1, end).trim()
                        val name = SqliteDdlScanning.constraintNameBefore(createSql, i)
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

}
