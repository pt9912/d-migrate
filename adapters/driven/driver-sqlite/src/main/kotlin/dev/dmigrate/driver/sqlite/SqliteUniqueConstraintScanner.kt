package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.sqlite.SqliteDdlScanning.isIdentifierChar

/**
 * Quote-aware scanner for table-level `UNIQUE (...)` clauses in a SQLite
 * CREATE TABLE statement. SQLite materialises inline UNIQUE constraints as
 * `sqlite_autoindex_*` entries whose constraint NAME lives only in the
 * `sqlite_master.sql` DDL text — this scanner recovers it (AP4 of the
 * postcompare-type-canonicalization slice; pattern precedent:
 * [SqliteCheckConstraintScanner]).
 *
 * Column-level `UNIQUE` (no paren group) is deliberately NOT matched — it
 * folds onto the column's `unique` flag via the autoindex column set and
 * needs no name recovery.
 */
internal object SqliteUniqueConstraintScanner {

    /** A table-level UNIQUE clause: optional constraint name + column list. */
    data class UniqueClause(val name: String?, val columns: List<String>)

    private const val UNIQUE_KEYWORD = "UNIQUE"

    fun scan(createSql: String): List<UniqueClause> {
        val clauses = mutableListOf<UniqueClause>()
        var i = 0
        while (i < createSql.length) {
            when (createSql[i]) {
                '\'', '"', '`' -> i = SqliteDdlScanning.skipQuoted(createSql, i)
                '[' -> i = SqliteDdlScanning.skipBracketIdentifier(createSql, i)
                else -> {
                    val end = if (SqliteDdlScanning.isKeywordAt(createSql, i, UNIQUE_KEYWORD)) {
                        SqliteDdlScanning.parenGroupEnd(createSql, i, UNIQUE_KEYWORD.length)
                    } else {
                        null
                    }
                    if (end != null) {
                        val open = createSql.indexOf('(', i)
                        val columns = parseColumnList(createSql.substring(open + 1, end))
                        if (columns.isNotEmpty()) {
                            clauses += UniqueClause(SqliteDdlScanning.constraintNameBefore(createSql, i), columns)
                        }
                        i = end + 1
                    } else {
                        i++
                    }
                }
            }
        }
        return clauses
    }

    /**
     * Splits the paren body on top-level commas and reduces each entry to its
     * leading identifier (quoted or bare) — trailing `COLLATE`/`ASC`/`DESC`
     * tokens are ignored.
     */
    private fun parseColumnList(body: String): List<String> {
        val entries = mutableListOf<String>()
        var depth = 0
        var start = 0
        var i = 0
        while (i <= body.length) {
            val c = body.getOrNull(i)
            when {
                c == null || (c == ',' && depth == 0) -> {
                    body.substring(start, i).trim().takeIf { it.isNotEmpty() }?.let { entries += it }
                    start = i + 1
                    i++
                }
                c == '(' -> { depth++; i++ }
                c == ')' -> { depth--; i++ }
                c == '\'' || c == '"' || c == '`' -> i = SqliteDdlScanning.skipQuoted(body, i)
                c == '[' -> i = SqliteDdlScanning.skipBracketIdentifier(body, i)
                else -> i++
            }
        }
        return entries.map(::leadingIdentifier)
    }

    private fun leadingIdentifier(entry: String): String = when (entry.first()) {
        '"', '`' -> SqliteDdlScanning.unquoteIdentifier(
            entry.substring(0, SqliteDdlScanning.skipQuoted(entry, 0)),
        )
        '[' -> SqliteDdlScanning.unquoteIdentifier(
            entry.substring(0, SqliteDdlScanning.skipBracketIdentifier(entry, 0)),
        )
        else -> entry.takeWhile { it.isIdentifierChar() }
    }
}
