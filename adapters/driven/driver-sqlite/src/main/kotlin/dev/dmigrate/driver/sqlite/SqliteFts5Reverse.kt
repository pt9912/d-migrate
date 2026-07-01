package dev.dmigrate.driver.sqlite

/**
 * ADR 0025 (Slice P5): FTS5 reverse — recognise the FULLTEXT-index expansion ([SqliteFullTextExpansion],
 * P4) and fold it back so it does not surface as user tables/triggers (which would drive a false
 * `migrate` post-compare drift). Per FULLTEXT index, P4 emits a `CREATE VIRTUAL TABLE <fts> USING
 * fts5(<cols>, content='<base>')` + FTS5-managed shadow tables + sync triggers `<fts>_{ai,ad,au}`.
 * On reverse ([SqliteSchemaReader]): the virtual table is reconstructed as a `FULLTEXT` index on its
 * content table, and the shadow tables + sync triggers are filtered — exactly mirroring the SpatiaLite
 * spatial-index fold ([SqliteTypeMapping] geometry filters).
 *
 * The parser is intentionally robust beyond P4's own output (arbitrary hand-authored fts5 can be
 * reversed): quote-/bracket-/paren-aware argument scanning, `UNINDEXED` column options, and
 * external-vs-regular shadow-table sets. Split out of [SqliteTypeMapping] to keep both objects
 * cohesive and under the Detekt function budget.
 */
internal object SqliteFts5Reverse {

    private val FTS5_USING = Regex("USING\\s+fts5\\s*\\(", RegexOption.IGNORE_CASE)
    private val INSERT_INTO = Regex("INSERT\\s+INTO", RegexOption.IGNORE_CASE)

    fun isFts5VirtualTable(createSql: String): Boolean =
        SqliteTypeMapping.isVirtualTable(createSql) && FTS5_USING.containsMatchIn(createSql)

    /** Parsed FTS5 virtual table: its [name], the external `content=` [contentTable] (null when
     *  contentless/omitted), and the indexed source [columns] (unquoted, in declaration order). */
    data class Fts5Definition(
        val name: String,
        val contentTable: String?,
        val columns: List<String>,
    )

    fun parseFts5(name: String, createSql: String): Fts5Definition {
        val open = FTS5_USING.find(createSql) ?: return Fts5Definition(name, null, emptyList())
        val args = extractBalancedArgs(createSql, open.range.last + 1)
            ?: return Fts5Definition(name, null, emptyList())
        val columns = mutableListOf<String>()
        var content: String? = null
        for (raw in splitTopLevelCommas(args)) {
            val part = raw.trim()
            if (part.isEmpty()) continue
            val eq = topLevelEqualsIndex(part)
            if (eq >= 0) {
                // fts5 option `key = value` (content, content_rowid, tokenize, prefix, …).
                if (part.substring(0, eq).trim().equals("content", ignoreCase = true)) {
                    content = unquote(part.substring(eq + 1).trim()).takeIf { it.isNotBlank() }
                }
            } else {
                // A column def is `<identifier> [UNINDEXED]` — take the identifier, drop options.
                columns += extractColumnName(part)
            }
        }
        return Fts5Definition(name, content, columns)
    }

    /**
     * FTS5-managed shadow tables for [ftsName]. External-content FTS5 (the form P4 emits) has
     * `_data/_idx/_docsize/_config` but **no** `_content` shadow — so it must not be filtered there
     * (else a real user table literally named `<fts>_content` would be dropped, "stiller
     * Datenverlust"). Regular/contentless FTS5 additionally owns `_content`. Lowercased.
     */
    fun fts5ShadowTables(ftsName: String, externalContent: Boolean): Set<String> {
        val suffixes = if (externalContent) {
            listOf("data", "idx", "docsize", "config")
        } else {
            listOf("data", "idx", "docsize", "config", "content")
        }
        return suffixes.map { "${ftsName}_$it".lowercase() }.toSet()
    }

    /** The three sync-trigger names [SqliteFullTextExpansion] installs for [ftsName]. Lowercased. */
    fun fts5SyncTriggerNames(ftsName: String): Set<String> =
        listOf("ai", "ad", "au").map { "${ftsName}_$it".lowercase() }.toSet()

    /**
     * Body-based FTS5 sync-trigger detection: a trigger that `INSERT INTO`s a known FTS5 table is
     * FTS5-internal, regardless of its name. Complements the exact-name match ([fts5SyncTriggerNames])
     * so custom-named sync triggers on a hand-authored fts5 don't leak into the neutral model.
     * [fts5TableNames] must be lowercased.
     */
    fun isFts5SyncTrigger(triggerSql: String, fts5TableNames: Set<String>): Boolean {
        if (fts5TableNames.isEmpty() || !INSERT_INTO.containsMatchIn(triggerSql)) return false
        val lower = triggerSql.lowercase()
        return fts5TableNames.any { lower.contains(it) }
    }

    /** The expected closing char for a quote/bracket opener, or null if [c] is not an opener. */
    private fun closingQuote(c: Char): Char? = when (c) {
        '\'' -> '\''
        '"' -> '"'
        '`' -> '`'
        '[' -> ']'
        else -> null
    }

    /** Balanced substring between the `(` at [start] and its matching `)` (quote-/bracket-aware), or null. */
    private fun extractBalancedArgs(sql: String, start: Int): String? {
        var depth = 1
        var close: Char? = null
        var i = start
        while (i < sql.length) {
            val c = sql[i]
            if (close != null) {
                if (c == close) close = null
            } else {
                val opened = closingQuote(c)
                when {
                    opened != null -> close = opened
                    c == '(' -> depth++
                    c == ')' -> { depth--; if (depth == 0) return sql.substring(start, i) }
                }
            }
            i++
        }
        return null
    }

    private fun splitTopLevelCommas(args: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var close: Char? = null
        for (c in args) {
            if (close != null) {
                sb.append(c); if (c == close) close = null
            } else {
                val opened = closingQuote(c)
                when {
                    opened != null -> { sb.append(c); close = opened }
                    c == '(' -> { sb.append(c); depth++ }
                    c == ')' -> { sb.append(c); depth-- }
                    c == ',' && depth == 0 -> { parts += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
            }
        }
        if (sb.isNotBlank()) parts += sb.toString()
        return parts
    }

    private fun topLevelEqualsIndex(part: String): Int {
        var close: Char? = null
        for (i in part.indices) {
            val c = part[i]
            if (close != null) {
                if (c == close) close = null
            } else {
                val opened = closingQuote(c)
                when {
                    opened != null -> close = opened
                    c == '=' -> return i
                }
            }
        }
        return -1
    }

    /** The leading (possibly quoted/bracketed) identifier of a column def, dropping trailing options
     *  like `UNINDEXED`. Doubled quote/bracket chars are treated as escapes. */
    private fun extractColumnName(token: String): String {
        val t = token.trim()
        if (t.isEmpty()) return t
        val close = closingQuote(t.first())
        if (close != null) {
            var i = 1
            while (i < t.length) {
                if (t[i] == close) {
                    if (i + 1 < t.length && t[i + 1] == close) { i += 2; continue } // escaped
                    return unquote(t.substring(0, i + 1))
                }
                i++
            }
            return unquote(t) // unterminated — best effort
        }
        return t.takeWhile { !it.isWhitespace() }
    }

    /** Strip one layer of SQL quoting (`"x"`, `'x'`, `` `x` ``, `[x]`) and unescape doubled quotes. */
    private fun unquote(value: String): String {
        val v = value.trim()
        if (v.length < 2) return v
        val first = v.first()
        val last = v.last()
        return when {
            first == '"' && last == '"' -> v.substring(1, v.length - 1).replace("\"\"", "\"")
            first == '\'' && last == '\'' -> v.substring(1, v.length - 1).replace("''", "'")
            first == '`' && last == '`' -> v.substring(1, v.length - 1).replace("``", "`")
            first == '[' && last == ']' -> v.substring(1, v.length - 1)
            else -> v
        }
    }
}
