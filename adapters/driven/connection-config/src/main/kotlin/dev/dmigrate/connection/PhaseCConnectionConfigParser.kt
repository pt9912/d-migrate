package dev.dmigrate.connection

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Plan-D §8.2 + §10.10 adapter-neutral parser for the **Phase-C
 * legacy** YAML format that the CLI's
 * `database.connections: { name: "jdbc-url-mit-${ENV}" }` shape
 * uses. Lives in `adapters/driven/connection-config` so both the
 * CLI's `NamedConnectionResolver` and any future MCP-side
 * runner can consult the same parser without duplicating
 * SnakeYAML wiring.
 *
 * **Important contract**: this parser is **secret-free**. It
 * returns the raw URL templates verbatim — `${VAR}` placeholders
 * are NEVER expanded here. ENV-substitution lives in the
 * CLI-adapter's `NamedConnectionResolver` (the only
 * authoritative runner path that needs the resolved URL).
 *
 * Distinct from [YamlConnectionReferenceLoader] which parses the
 * **Phase-D** map-form (with `displayName` / `dialectId` /
 * `sensitivity` / `credentialRef`) and emits
 * `ConnectionReference` records. Phase-C deployments that still
 * carry bare-URL connections continue to work via this legacy
 * parser; Phase-D deployments use the richer schema.
 */
object PhaseCConnectionConfigParser {

    /**
     * Loads the YAML and returns the connections map keyed by
     * connection-name. Values are raw URL templates including any
     * `${VAR}` placeholders. Returns an empty map when the
     * `database.connections` block is absent.
     */
    fun parseConnections(configPath: Path): Map<String, String> {
        val root = parseRoot(configPath) ?: return emptyMap()
        val database = root["database"] as? Map<*, *> ?: return emptyMap()
        val connectionsRaw = database["connections"] as? Map<*, *> ?: return emptyMap()
        return buildMap {
            for ((key, value) in connectionsRaw) {
                val name = key as? String ?: continue
                if (value is String) {
                    put(name, value)
                }
                // Object-form entries belong to the Phase-D
                // `YamlConnectionReferenceLoader`; the legacy
                // parser ignores them so a YAML can carry both
                // shapes during the Phase-C → Phase-D migration.
            }
        }
    }

    /**
     * Returns the raw `database.<key>` value (typically
     * `database.default_source` / `database.default_target`).
     * Returns `null` when the key is absent. Throws
     * [PhaseCConnectionConfigException] when present-but-not-a-
     * string.
     */
    fun parseDefault(configPath: Path, key: String): String? {
        val root = parseRoot(configPath) ?: return null
        val database = root["database"] as? Map<*, *> ?: return null
        val value = database[key] ?: return null
        return value as? String ?: throw PhaseCConnectionConfigException(
            "database.$key in $configPath must be a string, got ${value::class.simpleName}",
        )
    }

    private fun parseRoot(configPath: Path): Map<*, *>? {
        if (!Files.isRegularFile(configPath)) return null
        val parsed = try {
            val settings = LoadSettings.builder().build()
            Files.newInputStream(configPath).use { input ->
                Load(settings).loadFromInputStream(input)
            }
        } catch (t: Throwable) {
            throw PhaseCConnectionConfigException(
                "Failed to parse $configPath: ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }
        return parsed as? Map<*, *> ?: throw PhaseCConnectionConfigException(
            "Failed to parse $configPath: top-level YAML must be a mapping",
        )
    }
}

/**
 * Thrown when the legacy Phase-C YAML cannot be parsed or the
 * structure violates the documented format. The CLI-adapter's
 * `NamedConnectionResolver` wraps this into its
 * `ConfigResolveException` so the operator-facing CLI exit-code
 * mapping stays unchanged.
 */
open class PhaseCConnectionConfigException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
