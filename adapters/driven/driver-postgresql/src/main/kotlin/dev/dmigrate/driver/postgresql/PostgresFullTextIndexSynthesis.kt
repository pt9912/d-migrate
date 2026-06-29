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
 * This replaces the GiST-over-`tsvector` index with a `FULLTEXT` index over the
 * source columns (same index name, carrying the optional `textSearchConfig`). The
 * `tsvector` column stays a parameterless [NeutralType.FullText] and the trigger is
 * left untouched — PostgreSQL generate re-derives the GiST from the `FULLTEXT`
 * abstraction (keeping the PG→PG round-trip at 0 diffs). Triggers that are not a
 * recognised `tsvector_update_trigger(...)` call leave the model unchanged (no loss).
 */
internal object PostgresFullTextIndexSynthesis {

    /**
     * Matches the built-in `tsvector_update_trigger(<args>)` call inside a trigger
     * body (`EXECUTE FUNCTION tsvector_update_trigger(...)`). Deliberately does NOT
     * match the `tsvector_update_trigger_column(...)` variant — that signature
     * interleaves per-column weights and uses a config *column*, not a literal, so
     * its arguments are not the plain `(tsvcol, config, col…)` shape parsed here.
     */
    private val TSVECTOR_TRIGGER_REGEX = Regex(
        """tsvector_update_trigger\s*\(([^)]*)\)""",
        RegexOption.IGNORE_CASE,
    )

    data class ParsedTrigger(
        val table: String,
        val tsvectorColumn: String,
        val textSearchConfig: String?,
        val sourceColumns: List<String>,
    )

    /**
     * Parse a `tsvector_update_trigger('<tsvcol>', '<config>', '<col>'…)` trigger
     * body into its tsvector column, text-search config and source columns. Returns
     * null when the body is not a recognised tsvector-populating trigger (fewer than
     * the required `tsvcol, config, ≥1 source` arguments, or a different function).
     */
    fun parseTrigger(trigger: TriggerDefinition): ParsedTrigger? {
        val body = trigger.body ?: return null
        val match = TSVECTOR_TRIGGER_REGEX.find(body) ?: return null
        val args = splitArgs(match.groupValues[1])
        // tsvector_update_trigger(tsvcol, config, srccol, …) — needs at least one source.
        if (args.size < 3) return null
        val sourceColumns = args.drop(2)
        return ParsedTrigger(
            table = trigger.table,
            tsvectorColumn = args[0],
            textSearchConfig = normalizeConfig(args[1]),
            sourceColumns = sourceColumns,
        )
    }

    /**
     * Replace the GiST-over-`tsvector` index of each table with a `FULLTEXT` index
     * derived from that table's `tsvector_update_trigger`. Tables without such a
     * trigger — or whose tsvector column / GiST index cannot be matched — are
     * returned unchanged.
     */
    fun enrich(
        tables: Map<String, TableDefinition>,
        triggers: Map<String, TriggerDefinition>,
    ): Map<String, TableDefinition> {
        if (tables.isEmpty() || triggers.isEmpty()) return tables
        val parsedByTable = triggers.values
            .mapNotNull { parseTrigger(it) }
            .groupBy { it.table }
        if (parsedByTable.isEmpty()) return tables
        return tables.mapValues { (name, table) ->
            parsedByTable[name]?.let { enrichTable(table, it) } ?: table
        }
    }

    private fun enrichTable(table: TableDefinition, parsed: List<ParsedTrigger>): TableDefinition {
        var changed = false
        var indices = table.indices
        for (p in parsed) {
            // Only replace when the named column really is a tsvector (FullText) column.
            if (table.columns[p.tsvectorColumn]?.type !is NeutralType.FullText) continue
            indices = indices.map { idx ->
                if (idx.type == IndexType.GIST && idx.columnNames == listOf(p.tsvectorColumn)) {
                    changed = true
                    idx.copy(
                        type = IndexType.FULLTEXT,
                        columns = p.sourceColumns.map { IndexColumn(it) },
                        textSearchConfig = p.textSearchConfig,
                    )
                } else {
                    idx
                }
            }
        }
        return if (changed) table.copy(indices = indices) else table
    }

    /** Split a trigger argument list on commas and strip quotes/whitespace from each. */
    private fun splitArgs(argList: String): List<String> =
        argList.split(',')
            .map { unquote(it) }
            .filter { it.isNotBlank() }

    /**
     * Normalise a text-search config argument: strip quotes and the catalog prefix so
     * `'pg_catalog.english'` and `'english'` both become `english`. Blank → null.
     */
    private fun normalizeConfig(raw: String): String? =
        unquote(raw).substringAfterLast('.').ifBlank { null }

    private fun unquote(s: String): String =
        s.trim().removeSurrounding("'").removeSurrounding("\"").trim()
}
