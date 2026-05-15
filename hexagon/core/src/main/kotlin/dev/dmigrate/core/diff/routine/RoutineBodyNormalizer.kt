package dev.dmigrate.core.diff.routine

import java.security.MessageDigest

/**
 * E.1 Routine-Migration Slice A: canonical body normalisation +
 * SHA-256 hashing. The slice spec (plan-2 §9 E.1) demands that the
 * first routine slice avoid an SQL parser; equality is decided on a
 * conservative text normalisation:
 *
 * - Line endings collapsed to LF (`\r\n` and bare `\r` → `\n`).
 * - Trailing whitespace stripped from each line.
 * - Leading / trailing blank lines removed.
 * - Trailing semicolons stripped (any number of trailing `;` at the
 *   end of the body — that's the dialect's call, not the operator's
 *   identity, so `BEGIN END`, `BEGIN END;` and `BEGIN END;;` all
 *   hash the same).
 *
 * Inner whitespace, comments, casing and re-ordering stay
 * significant — they would all require semantic awareness to
 * collapse safely and that's deliberately out of scope for E.1.
 *
 * The hash is the SHA-256 of the normalised text encoded as UTF-8
 * lowercase hex. Operators see this hash in migration reports
 * instead of the raw body (see [RoutineBodyScrubber] for the
 * scrubbing contract that also applies before any preview is
 * rendered).
 */
object RoutineBodyNormalizer {

    /** Returns the canonical body text or `null` for a null/blank input. */
    fun normalise(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val lfNormalised = body.replace("\r\n", "\n").replace('\r', '\n')
        val trimmedLines = lfNormalised.split('\n').map { it.trimEnd() }
        val collapsed = trimmedLines.joinToString("\n").trim()
        return collapsed.trimEnd(';').trimEnd()
    }

    /** SHA-256 of the normalised body in lowercase hex, or `null` if the body is null/blank. */
    fun hash(body: String?): String? {
        val normalised = normalise(body) ?: return null
        val bytes = MessageDigest.getInstance("SHA-256").digest(normalised.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it) }
    }
}
