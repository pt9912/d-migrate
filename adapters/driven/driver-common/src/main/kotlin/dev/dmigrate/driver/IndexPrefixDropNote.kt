package dev.dmigrate.driver

import dev.dmigrate.core.model.IndexDefinition

/**
 * D-1: PostgreSQL and SQLite have no prefix-index concept. When an index column
 * carries a MySQL prefix length, those dialects index the **full** column (valid
 * DDL) and emit this note so the dropped prefix is not silent — the index's
 * selectivity/size may differ from the source.
 */
object IndexPrefixDropNote {

    fun forDialect(
        index: IndexDefinition,
        indexName: String,
        dialect: String,
        expressionHint: String,
    ): List<TransformationNote> {
        val prefixed = index.columns.firstOrNull { it.prefixLength != null } ?: return emptyList()
        return listOf(
            TransformationNote(
                type = NoteType.WARNING,
                code = "W126",
                objectName = indexName,
                message = "Prefix length on column '${prefixed.name}' was dropped: $dialect has no prefix-index " +
                    "concept; the full column is indexed.",
                hint = "If only a prefix should be indexed, use an expression index (e.g. $expressionHint).",
            )
        )
    }
}
