package dev.dmigrate.driver.sqlite

/**
 * ADR 0025 (Slice P5): FTS5 reverse — recognise the FULLTEXT-index expansion ([SqliteFullTextExpansion],
 * P4) and fold it back so it does not surface as user tables/triggers (which would drive a false
 * `migrate` post-compare drift). Per FULLTEXT index, P4 emits a `CREATE VIRTUAL TABLE <fts> USING
 * fts5(<cols>, content='<base>')` + FTS5-managed shadow tables `<fts>_{data,idx,docsize,config,content}`
 * + three sync triggers `<fts>_{ai,ad,au}`. On reverse ([SqliteSchemaReader]): the virtual table is
 * reconstructed as a `FULLTEXT` index on its content table, and the shadow tables + sync triggers are
 * filtered — exactly mirroring the SpatiaLite spatial-index fold ([SqliteTypeMapping] geometry filters).
 *
 * Split out of [SqliteTypeMapping] to keep both objects cohesive and under the Detekt function budget.
 */
internal object SqliteFts5Reverse {

    private val FTS5_USING = Regex("USING\\s+fts5\\s*\\(", RegexOption.IGNORE_CASE)

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
                columns += unquote(part)
            }
        }
        return Fts5Definition(name, content, columns)
    }

    /** FTS5-managed shadow tables for [ftsName] (external-content omits `_content`, but including it
     *  is harmless and covers contentless/regular FTS5 too). Lowercased for name-set membership. */
    fun fts5ShadowTables(ftsName: String): Set<String> =
        listOf("data", "idx", "docsize", "config", "content")
            .map { "${ftsName}_$it".lowercase() }.toSet()

    /** The three sync-trigger names [SqliteFullTextExpansion] installs for [ftsName]. Lowercased. */
    fun fts5SyncTriggerNames(ftsName: String): Set<String> =
        listOf("ai", "ad", "au").map { "${ftsName}_$it".lowercase() }.toSet()

    /** Balanced substring between the `(` at [start] and its matching `)` (quote-aware), or null. */
    private fun extractBalancedArgs(sql: String, start: Int): String? {
        var depth = 1
        var quote: Char? = null
        var i = start
        while (i < sql.length) {
            val c = sql[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '(' -> depth++
                c == ')' -> { depth--; if (depth == 0) return sql.substring(start, i) }
            }
            i++
        }
        return null
    }

    private fun splitTopLevelCommas(args: String): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for (c in args) {
            when {
                quote != null -> { sb.append(c); if (c == quote) quote = null }
                c == '\'' || c == '"' || c == '`' -> { sb.append(c); quote = c }
                c == '(' -> { sb.append(c); depth++ }
                c == ')' -> { sb.append(c); depth-- }
                c == ',' && depth == 0 -> { parts += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) parts += sb.toString()
        return parts
    }

    private fun topLevelEqualsIndex(part: String): Int {
        var quote: Char? = null
        for (i in part.indices) {
            val c = part[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '=' -> return i
            }
        }
        return -1
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
