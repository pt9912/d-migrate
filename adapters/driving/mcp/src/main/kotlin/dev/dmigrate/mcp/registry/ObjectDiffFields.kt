package dev.dmigrate.mcp.registry

import dev.dmigrate.core.diff.CustomTypeDiff
import dev.dmigrate.core.diff.FunctionDiff
import dev.dmigrate.core.diff.ProcedureDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.TriggerDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.ViewDiff

/**
 * Die Felder eines geaenderten Objekts, je unter ihrem **Dokument-Schluessel**
 * (`spec/schema-reference.md`) — der letzte Abschnitt eines Fund-Pfads
 * (`sequences.invoice_seq.min_value`). Ein nicht geaendertes Feld traegt
 * `null`.
 */
internal object ObjectDiffFields {

    fun of(diff: ViewDiff): List<Pair<String, ValueChange<*>?>> = listOf(
        "columns" to diff.columns,
        "query" to diff.query,
        "materialized" to diff.materialized,
        "refresh" to diff.refresh,
        "source_dialect" to diff.sourceDialect,
    )

    fun of(diff: SequenceDiff): List<Pair<String, ValueChange<*>?>> = listOf(
        "start" to diff.start,
        "increment" to diff.increment,
        "min_value" to diff.minValue,
        "max_value" to diff.maxValue,
        "cycle" to diff.cycle,
        "cache" to diff.cache,
    )

    fun of(diff: CustomTypeDiff): List<Pair<String, ValueChange<*>?>> = listOf(
        "kind" to diff.kind,
        "values" to diff.values,
        "base_type" to diff.baseType,
        "precision" to diff.precision,
        "scale" to diff.scale,
        "check" to diff.check,
        "description" to diff.description,
        "fields" to diff.fields,
    )

    fun of(diff: FunctionDiff): List<Pair<String, ValueChange<*>?>> = listOf(
        "parameters" to diff.parameters,
        "returns" to diff.returns,
        "language" to diff.language,
        "deterministic" to diff.deterministic,
        "body" to diff.body,
        "source_dialect" to diff.sourceDialect,
        "security" to diff.security,
        "definer" to diff.definer,
        "search_path" to diff.searchPath,
        "sql_mode" to diff.sqlMode,
    )

    fun of(diff: ProcedureDiff): List<Pair<String, ValueChange<*>?>> = listOf(
        "parameters" to diff.parameters,
        "language" to diff.language,
        "body" to diff.body,
        "source_dialect" to diff.sourceDialect,
        "security" to diff.security,
        "definer" to diff.definer,
        "search_path" to diff.searchPath,
        "sql_mode" to diff.sqlMode,
    )

    fun of(diff: TriggerDiff): List<Pair<String, ValueChange<*>?>> = listOf(
        "table" to diff.table,
        "event" to diff.event,
        "timing" to diff.timing,
        "for_each" to diff.forEach,
        "condition" to diff.condition,
        "body" to diff.body,
        "source_dialect" to diff.sourceDialect,
    )
}
