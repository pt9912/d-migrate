package dev.dmigrate.mcp.schema

/**
 * Wire constants for finding/strictness fields that appear across
 * LF-012 / LN-038 schema tools (LF-012 / LN-027 / LN-028 / LN-038 `schema_validate`, LF-012 / LN-027 / LN-028 / LN-038
 * `schema_generate` warnings, LF-012 / LN-027 / LN-028 / LN-038 `schema_compare` findings).
 *
 * Defining them once lets the JSON-Schema enums in
 * `McpToolSchemas` reference the same string literals as the
 * runtime emitter — drift between schema and handler is a compile
 * error, not a wire-format surprise.
 */
internal object SchemaFindingSeverity {
    const val ERROR: String = "error"
    const val WARNING: String = "warning"
    const val INFO: String = "info"
}

internal enum class Strictness(val wire: String) {
    LENIENT("lenient"),
    STRICT("strict");

    companion object {
        val WIRE_VALUES: List<String> = entries.map { it.wire }
        val ALLOWED: Set<String> = WIRE_VALUES.toSet()

        fun fromWire(value: String): Strictness =
            entries.first { it.wire == value }
    }
}
