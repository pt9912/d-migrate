package dev.dmigrate.server.application.audit

import dev.dmigrate.driver.connection.ConnectionSecretMasker

/**
 * Pattern-based scrubber that replaces known secret markers with a
 * fixed sentinel before audit emission. `AuditEvent` itself never
 * carries raw payloads — only fingerprints/IDs — so this scrubber
 * targets accidentally-leaked markers in `resourceRefs` strings.
 *
 * Connection-URL secrets (`user:pwd@host`, `?password=…`, `?token=…`,
 * `?api_key=…`, `?secret=…`, etc.) are delegated to
 * [ConnectionSecretMasker], which is the single source of truth for
 * URL/JDBC masking in the codebase. This scrubber adds two
 * audit-specific patterns on top:
 *
 * - OAuth-style `Bearer <token>` (Authorization-header leaks)
 * - Raw approval-token form `tok_<base62>` (a callsite forgot to use
 *   the fingerprint)
 *
 * Returns the input unchanged when no pattern matches.
 */
object SecretScrubber {

    private const val REDACTED = "***"

    private val BEARER = Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-]+""")
    private val APPROVAL_TOKEN = Regex("""tok_[A-Za-z0-9_\-]{8,}""")

    fun scrub(text: String): String {
        var out = ConnectionSecretMasker.mask(text)
        if (BEARER.containsMatchIn(out)) {
            out = BEARER.replace(out) { match -> "${match.groupValues[1]}$REDACTED" }
        }
        if (APPROVAL_TOKEN.containsMatchIn(out)) out = APPROVAL_TOKEN.replace(out, REDACTED)
        return out
    }
}
