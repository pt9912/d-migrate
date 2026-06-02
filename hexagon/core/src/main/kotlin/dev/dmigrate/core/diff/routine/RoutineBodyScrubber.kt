package dev.dmigrate.core.diff.routine

/**
 * E.1 Routine-Migration Slice A: masks secret-bearing string literals
 * in a routine body before the body (or any preview of it) is
 * surfaced in a report, diagnostic, or log line. Reports default to
 * `{ hash, length, scrubbedPreview, scrubbingApplied }`; the full
 * body never lands in an artefact until the F.2 body-embedding gate
 * exists.
 *
 * The patterns below are conservative: they match common credential
 * shapes (password literals, JDBC URLs, bearer tokens, AWS-style
 * access-key ids) without trying to parse SQL. False positives are
 * acceptable — masking a non-secret literal is harmless; missing a
 * real secret is not.
 */
object RoutineBodyScrubber {

    private const val MASK = "***SCRUBBED***"

    // Patterns are written as (prefix)(secret)(suffix?) so the
    // scrubber can always reconstruct the surrounding shape and mask
    // only the secret value. Single-capture patterns are rewritten
    // into prefix+secret+suffix shapes to keep the replacement logic
    // uniform.
    private val patterns: List<Regex> = listOf(
        // PASSWORD '<value>', PASSWORD "<value>" — single-quoted
        Regex("""(?i)(\b(?:password|pwd|passwd)\b\s*[:=]?\s*')([^']+)(')"""),
        // PASSWORD '<value>', PASSWORD "<value>" — double-quoted
        Regex("""(?i)(\b(?:password|pwd|passwd)\b\s*[:=]?\s*")([^"]+)(")"""),
        // Bearer/access tokens — single-quoted
        Regex("""(?i)(\b(?:token|secret|api[_-]?key|access[_-]?key)\b\s*[:=]?\s*')([^']+)(')"""),
        // Bearer/access tokens — double-quoted
        Regex("""(?i)(\b(?:token|secret|api[_-]?key|access[_-]?key)\b\s*[:=]?\s*")([^"]+)(")"""),
        // Unquoted password/secret/token assignment (loose form).
        // Conservative: requires at least 6 chars and stops at
        // whitespace/separator/quote so we don't accidentally
        // swallow trailing SQL syntax.
        Regex("""(?i)(\b(?:password|pwd|passwd|token|secret|api[_-]?key|access[_-]?key)\b\s*=\s*)([^\s;,'")]{6,})"""),
        // JDBC connection strings — mask password parameter only.
        // Lookahead instead of capture so a trailing separator
        // (`&`, `'`, whitespace) is preserved in the output instead
        // of being consumed.
        Regex("""(?i)(jdbc:[^'"\s]+?[?&]password=)([^'"&\s]+)"""),
        // postgres://user:password@host
        Regex("""(?i)((?:postgres|postgresql|mysql)://[^:/@\s]+:)([^@\s'"]+)(@)"""),
        // Generic AWS access key id
        Regex("""\b((?:AKIA|ASIA))([A-Z0-9]{16})\b"""),
    )

    /**
     * Returns the scrubbed body. `scrubbingApplied` reports whether
     * any pattern actually contributed a replacement so report
     * consumers can surface a `scrubbingApplied: true` flag instead
     * of silently masking.
     */
    fun scrub(body: String?): ScrubResult {
        if (body.isNullOrEmpty()) return ScrubResult(body ?: "", scrubbingApplied = false)
        var current: String = body
        var applied = false
        for (pattern in patterns) {
            val replaced = pattern.replace(current) { match ->
                val groups = match.groupValues
                val secret = if (groups.size >= 3) groups[2] else ""
                if (secret.isEmpty()) {
                    // No actual secret captured — leave the match untouched
                    // so we don't conjure a false positive.
                    match.value
                } else {
                    applied = true
                    val prefix = groups.getOrNull(1).orEmpty()
                    val suffix = groups.getOrNull(3).orEmpty()
                    prefix + MASK + suffix
                }
            }
            current = replaced
        }
        return ScrubResult(current, scrubbingApplied = applied)
    }

    /**
     * Builds the report-default preview shape. Limits the preview to
     * the first [previewLimit] characters of the scrubbed body to
     * keep reports compact; the full scrubbed body remains
     * accessible to consumers that explicitly opt in via a future
     * debug flag.
     */
    /**
     * Default `previewLimit` for [preview]. Exposed publicly so
     * report consumers can match the same threshold without
     * hard-coding it.
     */
    const val DEFAULT_PREVIEW_LIMIT: Int = 120

    fun preview(body: String?, previewLimit: Int = DEFAULT_PREVIEW_LIMIT): RoutineBodyPreview {
        val normalised = RoutineBodyNormalizer.normalise(body)
        val length = normalised?.length ?: 0
        val hash = RoutineBodyNormalizer.hash(body)
        if (normalised == null) {
            return RoutineBodyPreview(hash = null, length = 0, preview = "", scrubbingApplied = false)
        }
        val scrub = scrub(normalised)
        val preview = if (scrub.text.length <= previewLimit) {
            scrub.text
        } else {
            scrub.text.substring(0, previewLimit) + "…"
        }
        return RoutineBodyPreview(
            hash = hash,
            length = length,
            preview = preview,
            scrubbingApplied = scrub.scrubbingApplied,
        )
    }

}

/** Pair of [text] and [scrubbingApplied] returned by [RoutineBodyScrubber.scrub]. */
data class ScrubResult(val text: String, val scrubbingApplied: Boolean)

/**
 * Report-default body preview: hash + length + a scrubbed,
 * length-limited preview snippet. The full body is intentionally
 * absent from the preview — see [RoutineBodyScrubber] for the
 * rationale.
 */
data class RoutineBodyPreview(
    val hash: String?,
    val length: Int,
    val preview: String,
    val scrubbingApplied: Boolean,
)
