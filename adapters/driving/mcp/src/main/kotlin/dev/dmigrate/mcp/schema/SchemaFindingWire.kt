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

/**
 * Wire constants for the `status` field on `schema_generate`.
 *
 * `incomplete` heisst: der Lauf hat DDL erzeugt, aber mindestens ein Objekt
 * fiel heraus (`DdlResult.skippedObjects` ist nicht leer). Der Aufruf selbst
 * ist gelungen — deshalb bleibt er ein `ToolCallOutcome.Success` mit
 * `isError=false`, genau wie ein ungueltiges Schema bei `schema_validate`
 * `valid=false` traegt, ohne zum Transport-Fehler zu werden. `skippedCount`
 * traegt die Anzahl daneben, damit kein Konsument den Freitext-`summary`
 * parsen muss.
 *
 * Verhaelt sich zur CLI wie folgt: `d-migrate schema generate` endet bei
 * nicht-leerem `skippedObjects` mit Exit `8` (`--allow-incomplete` senkt das
 * auf `0`). Der MCP-Aufruf hat keinen Exit-Code; `incomplete` ist hier die
 * Entsprechung, und sie ist **unabhaengig davon immer gesetzt** — der
 * Aufrufer entscheidet, ob ihn das stoert.
 */
internal object SchemaGenerateStatus {
    const val COMPLETE: String = "complete"
    const val INCOMPLETE: String = "incomplete"
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
