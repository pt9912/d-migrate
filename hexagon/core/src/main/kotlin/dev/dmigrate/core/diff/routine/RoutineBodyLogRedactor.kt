package dev.dmigrate.core.diff.routine

/**
 * E.1 Routine-Migration — central log-redaction hook.
 *
 * Plan §4 demands that "ein zentraler Log-Redaction-Hook" sit
 * between routine-body data and any log / runner-trace / DB-error
 * channel: bodies must reach those channels via a single
 * normalisation point so an audit can prove no unmasked body
 * snippet leaks without `--debug-body`.
 *
 * This object is that point. Production code that touches
 * routine-body text on its way into a log line, runner trace, or
 * diagnostic message MUST route the value through [redact]:
 *
 * ```kotlin
 * val safe = RoutineBodyLogRedactor.redact(rawBody, allowRaw = request.debugBody)
 * log.info("rendered routine body: {}", safe)
 * ```
 *
 * The redactor reuses [RoutineBodyScrubber] so the redaction
 * vocabulary stays in sync with the report-render path; a single
 * change to the credential-pattern catalogue covers both planes.
 *
 * `allowRaw = true` bypasses redaction and is reserved for the
 * `--debug-body` CLI path (see
 * `dev.dmigrate.driver.RoutineBodyDisplay.RAW_DEBUG` in
 * `hexagon:ports-read`). The boolean is preferred over an enum
 * parameter so the core module avoids a hard dependency on the
 * ports-read layer.
 *
 * Today no production code path emits routine bodies into logger
 * output — but the renderer / runner code is allowed to grow such
 * paths without re-introducing a leak risk: the hook is here,
 * tested, and ready to be called.
 */
object RoutineBodyLogRedactor {

    /**
     * Returns a log-safe representation of [text]. When [allowRaw]
     * is `false` (the default), credential-bearing substrings are
     * replaced with the same `***SCRUBBED***` marker
     * [RoutineBodyScrubber] uses for report previews. When
     * `allowRaw` is `true`, the text is returned verbatim — only
     * the `--debug-body` unsafe path passes this flag.
     *
     * `null` input passes through unchanged so callers can chain
     * the redactor without an outer null-guard.
     */
    fun redact(text: String?, allowRaw: Boolean = false): String? {
        if (text == null) return null
        if (allowRaw) return text
        return RoutineBodyScrubber.scrub(text).text
    }
}
