package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition

/**
 * P2 (ADR 0025): derive a neutral [IndexType.FULLTEXT] index from a PostgreSQL
 * `tsvector_update_trigger(...)` populating trigger.
 *
 * PostgreSQL stores a *precomputed* `tsvector` in its own column; the human-readable
 * **source text columns** and the text-search **config** live only in the trigger
 * arguments (`tsvector_update_trigger('<tsvcol>', '<config>', '<col1>', '<col2>', …)`)
 * — the `tsvector` itself carries no readable text. To round-trip the fulltext
 * *capability* (MySQL `FULLTEXT`, SQLite FTS5) the source columns must be recovered.
 *
 * This replaces the GiST-/GIN-over-`tsvector` index with a `FULLTEXT` index over the
 * source columns (same index name; carrying the optional `textSearchConfig` and the
 * backing tsvector column in [IndexDefinition.fullTextVectorColumn] so PG generate/diff
 * can rebuild the index on the right vector column even with several tsvector columns
 * per table). The `tsvector` column stays a parameterless [NeutralType.FullText] and the
 * trigger is left untouched. Triggers that are not a recognised `tsvector_update_trigger`
 * call leave the model unchanged (no loss).
 */
internal object PostgresFullTextIndexSynthesis {

    /**
     * Anchors the built-in `tsvector_update_trigger(` call inside a trigger body
     * (`EXECUTE FUNCTION tsvector_update_trigger(…)`). The negative lookbehind requires
     * a non-identifier char (or start) before the name, so neither the
     * `tsvector_update_trigger_column(…)` variant (different signature) nor a
     * user-defined wrapper such as `app_tsvector_update_trigger(…)` is matched. The
     * argument list is parsed separately (quote-/paren-aware) starting at the `(`.
     */
    private val TRIGGER_CALL_REGEX = Regex(
        """(?<![A-Za-z0-9_])tsvector_update_trigger\s*\(""",
        RegexOption.IGNORE_CASE,
    )

    private const val PG_CATALOG_PREFIX = "pg_catalog."

    /** Index access methods PostgreSQL accepts for a `tsvector` column (GiST + GIN). */
    private val VECTOR_INDEX_TYPES = setOf(IndexType.GIST, IndexType.GIN)

    data class ParsedTrigger(
        val table: String,
        val tsvectorColumn: String,
        val textSearchConfig: String?,
        val sourceColumns: List<String>,
    )

    /**
     * Parse a `tsvector_update_trigger('<tsvcol>', '<config>', '<col>'…)` trigger body
     * into its tsvector column, text-search config and source columns. Returns null when
     * the body is not a recognised tsvector-populating trigger (different function, no
     * matching close paren, fewer than the required `tsvcol, config, ≥1 source` arguments,
     * or a blank tsvector column / source column).
     */
    fun parseTrigger(trigger: TriggerDefinition): ParsedTrigger? {
        val body = trigger.body ?: return null
        val open = TRIGGER_CALL_REGEX.find(body) ?: return null
        val args = parseArgList(body, open.range.last + 1) ?: return null
        // tsvector_update_trigger(tsvcol, config, srccol, …) — positional, ≥3 args.
        if (args.size < 3) return null
        val tsvectorColumn = args[0].trim()
        if (tsvectorColumn.isEmpty()) return null
        val sourceColumns = args.drop(2).map { it.trim() }.filter { it.isNotEmpty() }
        if (sourceColumns.isEmpty()) return null
        return ParsedTrigger(
            table = trigger.table,
            tsvectorColumn = tsvectorColumn,
            textSearchConfig = normalizeConfig(args[1]),
            sourceColumns = sourceColumns,
        )
    }

    /**
     * Replace the GiST/GIN-over-`tsvector` index of each table with a `FULLTEXT` index
     * derived from that table's `tsvector_update_trigger`. Tables without such a trigger —
     * or whose tsvector column / backing index cannot be matched — are returned unchanged.
     */
    fun enrich(
        tables: Map<String, TableDefinition>,
        triggers: Map<String, TriggerDefinition>,
    ): Map<String, TableDefinition> {
        val parsedByTable = triggers.values
            .mapNotNull { parseTrigger(it) }
            .groupBy { it.table }
        return tables.mapValues { (name, table) ->
            parsedByTable[name]?.let { enrichTable(table, it) } ?: table
        }
    }

    private fun enrichTable(table: TableDefinition, parsed: List<ParsedTrigger>): TableDefinition {
        // Only triggers whose named column really is a tsvector (FullText) column.
        val byVectorColumn = parsed
            .filter { table.columns[it.tsvectorColumn]?.type is NeutralType.FullText }
            .associateBy { it.tsvectorColumn }
        if (byVectorColumn.isEmpty()) return table
        var replaced = false
        val indices = table.indices.map { idx ->
            val p = idx.takeIf { it.type in VECTOR_INDEX_TYPES }
                ?.columnNames?.singleOrNull()
                ?.let { byVectorColumn[it] }
                ?: return@map idx
            replaced = true
            idx.copy(
                type = IndexType.FULLTEXT,
                columns = p.sourceColumns.map { IndexColumn(it) },
                textSearchConfig = p.textSearchConfig,
                fullTextVectorColumn = p.tsvectorColumn,
            )
        }
        return if (replaced) table.copy(indices = indices) else table
    }

    /**
     * Quote-/paren-aware parse of a SQL argument list starting just after its opening
     * `(`. Single-quoted string literals (with `''` escapes) are unwrapped to their
     * content; commas and parentheses inside a literal are not treated as separators.
     * Returns the per-argument content (positional, including empty positions) up to the
     * matching top-level `)`, or null when no closing paren is found.
     */
    private fun parseArgList(s: String, start: Int): List<String>? {
        val args = mutableListOf<String>()
        val cur = StringBuilder()
        var i = start
        var inQuote = false
        while (i < s.length) {
            val c = s[i]
            when {
                inQuote -> when {
                    c == '\'' && i + 1 < s.length && s[i + 1] == '\'' -> { cur.append('\''); i++ }
                    c == '\'' -> inQuote = false
                    else -> cur.append(c)
                }
                c == '\'' -> inQuote = true
                c == ',' -> { args += cur.toString(); cur.setLength(0) }
                c == ')' -> { args += cur.toString(); return args }
                else -> cur.append(c)
            }
            i++
        }
        return null
    }

    /**
     * Normalise a text-search config: strip only the `pg_catalog.` prefix
     * (`pg_catalog.english` → `english`) while keeping a user-schema qualifier intact
     * (`myschema.german` stays). Blank → null.
     */
    private fun normalizeConfig(raw: String): String? {
        val v = raw.trim()
        return when {
            v.isEmpty() -> null
            v.regionMatches(0, PG_CATALOG_PREFIX, 0, PG_CATALOG_PREFIX.length, ignoreCase = true) ->
                v.substring(PG_CATALOG_PREFIX.length)
            else -> v
        }
    }
}
