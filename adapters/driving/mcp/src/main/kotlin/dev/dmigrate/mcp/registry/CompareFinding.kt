package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.schema.SchemaFindingSeverity
import dev.dmigrate.server.application.audit.SecretScrubber

/**
 * Die Bausteine eines `schema_compare`-Fundes: `severity`, `code`, `path`,
 * `message` und optional `details` (`before`/`after`). Geteilt von
 * [SchemaCompareFindings] und [TableCompareFindings].
 */
internal object CompareFinding {

    /**
     * Builds a finding record. `details` is the LF-012 / LN-027 / LN-028 / LN-038 machine-
     * readable supplement to `message`; both pass through
     * [SecretScrubber] before serialisation so connection URLs,
     * bearer tokens, and approval-token literals can't leak via the
     * wire. `message` stays human-readable per the §6.13
     * backward-compat rule.
     */
    fun finding(
        severity: String,
        code: String,
        path: String,
        message: String,
        details: Map<String, String>? = null,
    ): Map<String, Any?> = buildMap {
        put("severity", severity)
        put("code", code)
        put("path", path)
        put("message", SecretScrubber.scrub(message))
        if (!details.isNullOrEmpty()) {
            put("details", details.mapValues { (_, v) -> SecretScrubber.scrub(v) })
        }
    }

    /**
     * Standard `{ before, after }` shape for property-level changes.
     * LF-012 / LN-027 / LN-028 / LN-038: the output schema rejects blanks / pure whitespace via
     * `pattern: "\\S"` and demands at least one of the two fields
     * (`minProperties: 1`). Drop null / blank sides defensively so a
     * missing operand never lands as `"null"` or `""` in the wire
     * payload; if both sides are blank, `finding(...)` skips the
     * `details` slot entirely (additive/removal-style behaviour).
     */
    fun beforeAfter(before: Any?, after: Any?): Map<String, String> {
        val result = mutableMapOf<String, String>()
        before?.toString()?.takeIf { it.isNotBlank() }?.let { result["before"] = it }
        after?.toString()?.takeIf { it.isNotBlank() }?.let { result["after"] = it }
        return result
    }

    // Additive changes are non-breaking by default — surface as info
    // so clients can filter the noise out of the warning channel.
    fun added(code: String, path: String, details: Map<String, String>? = null): Map<String, Any?> =
        finding(SchemaFindingSeverity.INFO, code, path, "$path was added", details)

    // Removed/changed objects are potentially breaking; clients
    // typically gate deploys on the warning bucket.
    fun removed(code: String, path: String, details: Map<String, String>? = null): Map<String, Any?> =
        finding(SchemaFindingSeverity.WARNING, code, path, "$path was removed", details)

    fun changed(code: String, path: String, details: Map<String, String>? = null): Map<String, Any?> =
        finding(SchemaFindingSeverity.WARNING, code, path, "$path changed", details)
}
