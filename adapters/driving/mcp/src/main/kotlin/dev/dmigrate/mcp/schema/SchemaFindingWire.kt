package dev.dmigrate.mcp.schema

/**
 * Wire constants for finding/strictness fields that appear across
 * Phase-C schema tools (AP 6.4 `schema_validate`, AP 6.5
 * `schema_generate` warnings, AP 6.6 `schema_compare` findings).
 *
 * Defining them once lets the JSON-Schema enums in
 * `PhaseBToolSchemas` reference the same string literals as the
 * runtime emitter — drift between schema and handler is a compile
 * error, not a wire-format surprise.
 */
internal object SchemaFindingSeverity {
    const val ERROR: String = "error"
    const val WARNING: String = "warning"
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
