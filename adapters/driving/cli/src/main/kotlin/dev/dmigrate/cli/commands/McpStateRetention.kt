package dev.dmigrate.cli.commands

import java.time.Duration
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Orphan-retention policy for the §6.21 startup sweep.
 *
 * - [Never]: skip the sweep entirely (forensic mode — keep crashed
 *   sessions and unreferenceable artefacts on disk for inspection).
 * - [Immediate]: drop every store file at boot regardless of age.
 *   Maps to the input strings `0` and `0s`.
 * - [After]: keep store files newer than [duration]; everything older
 *   is removed.
 */
internal sealed class RetentionPolicy {
    object Never : RetentionPolicy()
    object Immediate : RetentionPolicy()
    data class After(val duration: Duration) : RetentionPolicy()
}

internal object RetentionParser {
    const val ENV_VAR: String = "DMIGRATE_MCP_STATE_ORPHAN_RETENTION"
    const val DEFAULT_INPUT: String = "24h"

    /**
     * Resolution order per §6.21:
     *  1. [cliOption] — `--mcp-state-orphan-retention`
     *  2. environment variable [ENV_VAR]
     *  3. [DEFAULT_INPUT] (`24h`)
     *
     * Throws [StateDirConfigError] for any unrecognised input — the
     * CLI maps that to exit 2 before lock acquisition.
     */
    fun resolve(
        cliOption: String?,
        env: (String) -> String? = System::getenv,
    ): RetentionPolicy {
        val raw = cliOption
            ?: env(ENV_VAR)?.takeUnless { it.isBlank() }
            ?: DEFAULT_INPUT
        return parse(raw)
    }

    fun parse(input: String): RetentionPolicy {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) reject(input, "empty value")
        val lower = trimmed.lowercase(Locale.ROOT)
        return when {
            lower == "never" -> RetentionPolicy.Never
            lower == "0" || lower == "0s" -> RetentionPolicy.Immediate
            COMPACT_REGEX.matches(lower) -> parseCompact(input, lower)
            lower.startsWith("p") -> parseIso(input, trimmed)
            else -> reject(
                input,
                "expected `never`, `0`, `0s`, <number><ms|s|m|h|d>, or ISO-8601 PT…",
            )
        }
    }

    private fun parseCompact(input: String, lower: String): RetentionPolicy {
        val match = COMPACT_REGEX.matchEntire(lower)!!
        val (numberStr, unit) = match.destructured
        val amount = numberStr.toLongOrNull() ?: reject(input, "number out of range")
        if (amount <= 0) reject(input, "must be positive — use `0`/`0s` for immediate")
        val duration = when (unit) {
            "ms" -> Duration.ofMillis(amount)
            "s" -> Duration.ofSeconds(amount)
            "m" -> Duration.ofMinutes(amount)
            "h" -> Duration.ofHours(amount)
            "d" -> Duration.ofDays(amount)
            else -> reject(input, "unknown unit $unit")
        }
        return RetentionPolicy.After(duration)
    }

    private fun parseIso(input: String, trimmed: String): RetentionPolicy {
        val parsed = try {
            Duration.parse(trimmed.uppercase(Locale.ROOT))
        } catch (failure: DateTimeParseException) {
            throw StateDirConfigError(
                "invalid orphan retention: $input (not a valid ISO-8601 duration)",
                cause = failure,
            )
        }
        if (parsed.isZero || parsed.isNegative) {
            reject(input, "must be positive — use `0`/`0s` for immediate")
        }
        return RetentionPolicy.After(parsed)
    }

    private fun reject(input: String, reason: String): Nothing {
        throw StateDirConfigError("invalid orphan retention: $input ($reason)")
    }

    private val COMPACT_REGEX = Regex("^(\\d+)(ms|s|m|h|d)$")
}
