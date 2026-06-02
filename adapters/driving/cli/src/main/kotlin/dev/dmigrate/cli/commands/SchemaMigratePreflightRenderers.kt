package dev.dmigrate.cli.commands

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice F follow-up
 * (2026-05-20): preflight-view JSON projections, split out of
 * [SchemaMigrateReportRenderer] so the main renderer stays under
 * Detekt's `TooManyFunctions` budget when more preflight kinds
 * land (today: SQLite-cast + MySQL-sequence-canonicity).
 *
 * Keeps the JSON wire shape stable: each function takes a
 * pre-built list of view DTOs and emits a JSON array — no formatter
 * dependency, no buffer ownership, no nesting. Both functions are
 * called via [SchemaMigrateReportRenderer.appendField], which wraps
 * them with `"key": …,`.
 */
internal object SchemaMigratePreflightRenderers {

    fun renderSqliteCastPreflights(preflights: List<SchemaMigrateSqliteCastPreflightView>): String =
        preflights.joinToString(prefix = "[", postfix = "]", separator = ",") { preflight ->
            "{\"operationId\":${jsonString(preflight.operationId)}," +
                "\"dialect\":${jsonString(preflight.dialect)}," +
                "\"table\":${jsonString(preflight.table)}," +
                "\"column\":${jsonString(preflight.column)}," +
                "\"sourceType\":${jsonString(preflight.sourceType)}," +
                "\"targetType\":${jsonString(preflight.targetType)}," +
                "\"status\":${jsonString(preflight.status)}," +
                "\"sqlHash\":${jsonString(preflight.sqlHash)}," +
                "\"totalRows\":${preflight.totalRows ?: "null"}," +
                "\"failingRows\":${preflight.failingRows ?: "null"}," +
                "\"sampleRowIds\":${jsonStringArray(preflight.sampleRowIds)}," +
                "\"problem\":${jsonOptString(preflight.problem)}}"
        }

    fun renderMysqlSequenceCanonicity(
        declarations: List<SchemaMigrateMysqlSequenceCanonicityView>,
    ): String = declarations.joinToString(prefix = "[", postfix = "]", separator = ",") { declaration ->
        "{\"operationId\":${jsonString(declaration.operationId)}," +
            "\"dialect\":${jsonString(declaration.dialect)}," +
            "\"kind\":${jsonString(declaration.kind)}," +
            "\"objectName\":${jsonString(declaration.objectName)}," +
            "\"status\":${jsonString(declaration.status)}," +
            "\"sqlHash\":${jsonString(declaration.sqlHash)}," +
            "\"driftField\":${jsonOptString(declaration.driftField)}," +
            "\"expected\":${jsonOptString(declaration.expected)}," +
            "\"actual\":${jsonOptString(declaration.actual)}," +
            "\"problem\":${jsonOptString(declaration.problem)}}"
    }

    // Mirror the small JSON helpers from `SchemaMigrateReportRenderer`
    // so this object stays standalone. Kept private to its file.
    private fun jsonStringArray(elements: List<String>): String =
        elements.joinToString(prefix = "[", postfix = "]", separator = ",") { jsonString(it) }

    private fun jsonOptString(s: String?): String = if (s == null) "null" else jsonString(s)

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
