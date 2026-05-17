package dev.dmigrate.server.application.routine

import dev.dmigrate.driver.EffectiveRoutineCapability
import dev.dmigrate.driver.MysqlServerVersion
import dev.dmigrate.driver.RoutineKind
import dev.dmigrate.driver.RoutineKindCapability

/**
 * 0.9.7 routine-capability-configurable-source Sub-Slice A: pure
 * string → [EffectiveRoutineCapability] parser for operator-supplied
 * capability mappings.
 *
 * Two input channels, both already extracted by the CLI adapter
 * (Sub-Slice B does the SnakeYAML / Clikt I/O):
 *
 * - `cliFlagValues` — raw repeatable values of `--routine-capability`,
 *   in command-line order. Each value matches the grammar
 *   `<kind>:<key>=<value>[,<key>=<value>...]` (see plan §6.1).
 * - `yamlSection` — the deserialised mapping below `routineCapability:`
 *   in `.d-migrate.yaml` (see plan §6.2), or `null` if the key is
 *   absent.
 *
 * Precedence per routine kind (plan §1): CLI > YAML > `defaults`.
 * Merging happens kind-by-kind, so CLI for `function` plus YAML for
 * `procedure` yields a `Valid` envelope that picks the right slot from
 * each source — and CLI for `function` plus YAML for `function` keeps
 * the CLI value (plan §8 risk note: CLI wins, no conflict-Invalid).
 *
 * Any structural defect (unknown kind, unknown key, unparsable bool,
 * unparsable version, duplicate `--routine-capability` per kind,
 * non-Map YAML value, wrong YAML scalar type) collapses the entire
 * envelope to [EffectiveRoutineCapability.Invalid] with a descriptive
 * `reason`; the renderer then blocks every routine op with
 * `ROUTINE_CAPABILITY_CONFIG_INVALID` (plan §6.3).
 */
object RoutineCapabilityConfigParser {

    private const val KIND_FUNCTION = "function"
    private const val KIND_PROCEDURE = "procedure"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MIN_SERVER_VERSION = "minServerVersion"

    fun parse(
        cliFlagValues: List<String>,
        yamlSection: Map<String, Any?>?,
        defaults: EffectiveRoutineCapability.Valid,
    ): EffectiveRoutineCapability = try {
        val cli = parseCli(cliFlagValues)
        val yaml = parseYaml(yamlSection)
        EffectiveRoutineCapability.Valid(
            function = cli[RoutineKind.FUNCTION] ?: yaml[RoutineKind.FUNCTION] ?: defaults.function,
            procedure = cli[RoutineKind.PROCEDURE] ?: yaml[RoutineKind.PROCEDURE] ?: defaults.procedure,
        )
    } catch (e: ConfigInvalid) {
        EffectiveRoutineCapability.Invalid(e.reason)
    }

    private fun parseCli(values: List<String>): Map<RoutineKind, RoutineKindCapability> {
        val out = LinkedHashMap<RoutineKind, RoutineKindCapability>()
        for (raw in values) {
            val idx = raw.indexOf(':')
            if (idx <= 0 || idx == raw.length - 1) {
                invalid(
                    "invalid --routine-capability syntax (expected '<kind>:<key>=<value>[,<key>=<value>...]'): '$raw'",
                )
            }
            val kindKey = raw.substring(0, idx)
            val pairs = raw.substring(idx + 1)
            val kind = parseKindKey(kindKey, source = "CLI")
            if (out.containsKey(kind)) {
                invalid("duplicate --routine-capability for kind=$kindKey")
            }
            out[kind] = parseCliPairs(kindKey, pairs)
        }
        return out
    }

    private fun parseCliPairs(kindKey: String, pairs: String): RoutineKindCapability {
        val entries = pairs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) {
            invalid("--routine-capability for kind=$kindKey has no key=value pairs")
        }
        val acc = KindCapabilityAccumulator()
        for (entry in entries) {
            applyCliPair(kindKey, entry, acc)
        }
        return acc.build {
            invalid("--routine-capability for kind=$kindKey is missing required key 'enabled'")
        }
    }

    private fun applyCliPair(kindKey: String, entry: String, into: KindCapabilityAccumulator) {
        val eq = entry.indexOf('=')
        if (eq <= 0 || eq == entry.length - 1) {
            invalid("--routine-capability for kind=$kindKey has malformed key=value pair: '$entry'")
        }
        val key = entry.substring(0, eq).trim()
        val value = entry.substring(eq + 1).trim()
        when (key) {
            KEY_ENABLED -> into.enabled = parseBoolStrict(value)
                ?: invalid(
                    "--routine-capability for kind=$kindKey has invalid 'enabled' value '$value' " +
                        "(expected 'true' or 'false')",
                )
            KEY_MIN_SERVER_VERSION -> into.minServerVersion = MysqlServerVersion.parse(value)
                ?: invalid(
                    "--routine-capability for kind=$kindKey has unparsable 'minServerVersion' " +
                        "value '$value' (expected major.minor.patch)",
                )
            else -> invalid(
                "--routine-capability for kind=$kindKey has unknown key '$key' " +
                    "(allowed: $KEY_ENABLED, $KEY_MIN_SERVER_VERSION)",
            )
        }
    }

    private fun parseYaml(section: Map<String, Any?>?): Map<RoutineKind, RoutineKindCapability> {
        if (section.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<RoutineKind, RoutineKindCapability>()
        for ((key, value) in section) {
            val kind = parseKindKey(key, source = "YAML 'routineCapability'")
            val map = value as? Map<*, *>
                ?: invalid(
                    "YAML 'routineCapability.$key' must be a mapping with keys '$KEY_ENABLED' / " +
                        "'$KEY_MIN_SERVER_VERSION', got ${describeYamlScalar(value)}",
                )
            out[kind] = parseYamlKind(key, map)
        }
        return out
    }

    private fun parseYamlKind(kindKey: String, map: Map<*, *>): RoutineKindCapability {
        val acc = KindCapabilityAccumulator()
        for ((rawKey, rawVal) in map) {
            applyYamlPair(kindKey, rawKey, rawVal, acc)
        }
        return acc.build {
            invalid("YAML 'routineCapability.$kindKey' is missing required key '$KEY_ENABLED'")
        }
    }

    private fun applyYamlPair(kindKey: String, rawKey: Any?, rawVal: Any?, into: KindCapabilityAccumulator) {
        val key = rawKey as? String
            ?: invalid("YAML 'routineCapability.$kindKey' has non-string key '$rawKey'")
        when (key) {
            KEY_ENABLED -> into.enabled = rawVal as? Boolean
                ?: invalid(
                    "YAML 'routineCapability.$kindKey.$KEY_ENABLED' must be a boolean, " +
                        "got ${describeYamlScalar(rawVal)}",
                )
            KEY_MIN_SERVER_VERSION -> into.minServerVersion = parseYamlMinServerVersion(kindKey, rawVal)
            else -> invalid(
                "YAML 'routineCapability.$kindKey' has unknown key '$key' " +
                    "(allowed: $KEY_ENABLED, $KEY_MIN_SERVER_VERSION)",
            )
        }
    }

    private fun parseYamlMinServerVersion(kindKey: String, rawVal: Any?): MysqlServerVersion {
        val str = rawVal as? String
            ?: invalid(
                "YAML 'routineCapability.$kindKey.$KEY_MIN_SERVER_VERSION' must be a quoted string " +
                    "to avoid YAML float coercion (e.g. \"8.0.0\"), got ${describeYamlScalar(rawVal)}",
            )
        return MysqlServerVersion.parse(str)
            ?: invalid(
                "YAML 'routineCapability.$kindKey.$KEY_MIN_SERVER_VERSION' is unparsable: '$str' " +
                    "(expected major.minor.patch)",
            )
    }

    private fun parseKindKey(key: String, source: String): RoutineKind = when (key) {
        KIND_FUNCTION -> RoutineKind.FUNCTION
        KIND_PROCEDURE -> RoutineKind.PROCEDURE
        else -> invalid("$source declares unknown routine kind '$key' (allowed: $KIND_FUNCTION, $KIND_PROCEDURE)")
    }

    private fun parseBoolStrict(value: String): Boolean? = when (value) {
        "true" -> true
        "false" -> false
        else -> null
    }

    private fun describeYamlScalar(value: Any?): String = when (value) {
        null -> "null"
        else -> "'$value' (${value.javaClass.simpleName})"
    }

    private fun invalid(reason: String): Nothing = throw ConfigInvalid(reason)

    private class ConfigInvalid(val reason: String) : RuntimeException(reason)

    /**
     * Per-kind value accumulator shared by the CLI and YAML pair
     * walkers. `enabled` is the only required field; `minServerVersion`
     * stays `null` when the operator omits it. [build] runs the
     * missing-`enabled` invariant when both walkers have finished, so
     * the partial-state check lives in one place instead of two.
     */
    private class KindCapabilityAccumulator {
        var enabled: Boolean? = null
        var minServerVersion: MysqlServerVersion? = null

        inline fun build(onMissingEnabled: () -> Nothing): RoutineKindCapability {
            val enabledValue = enabled ?: onMissingEnabled()
            return RoutineKindCapability(enabled = enabledValue, minServerVersion = minServerVersion)
        }
    }
}
