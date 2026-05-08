package dev.dmigrate.cli.commands

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * LF-012 / LN-011 / LN-017 / LN-027 server-state configuration for `mcp serve`.
 *
 * Resolution order per field: `D_MIGRATE_SERVER_STATE_*` env override,
 * then `server.state.*` from the effective YAML config. If neither
 * config nor env supplies `jdbcUrl`, the CLI keeps the legacy
 * in-process metadata wiring.
 */
internal data class McpServerStateConfig(
    val jdbcUrl: String,
    val username: String?,
    val password: String?,
    val maximumPoolSize: Int,
    val connectionTimeoutMs: Long,
    val migrationsAuto: Boolean,
) {
    companion object {
        const val DEFAULT_MAXIMUM_POOL_SIZE: Int = 10
        const val DEFAULT_CONNECTION_TIMEOUT_MS: Long = 30_000
    }
}

internal class McpServerStateConfigError(message: String, cause: Throwable? = null) : Exception(message, cause)

internal class McpServerStateConfigResolver(
    private val configPath: Path?,
    private val envLookup: (String) -> String? = System::getenv,
) {
    fun resolve(): McpServerStateConfig? {
        val yamlState = configPath?.let(::loadYamlState).orEmpty()
        val envPresent = STATE_ENV_KEYS.any { !envLookup(it).isNullOrBlank() }
        if (yamlState.isEmpty() && !envPresent) return null

        val jdbcUrl = resolveString("D_MIGRATE_SERVER_STATE_JDBC_URL", yamlState, "jdbcUrl")
            ?: throw McpServerStateConfigError(
                "server.state.jdbcUrl is required when server-state persistence is configured",
            )
        return McpServerStateConfig(
            jdbcUrl = jdbcUrl,
            username = resolveString("D_MIGRATE_SERVER_STATE_USERNAME", yamlState, "username"),
            password = resolveString("D_MIGRATE_SERVER_STATE_PASSWORD", yamlState, "password"),
            maximumPoolSize = resolveInt(
                "D_MIGRATE_SERVER_STATE_HIKARI_MAXIMUM_POOL_SIZE",
                yamlState,
                "hikari.maximumPoolSize",
                McpServerStateConfig.DEFAULT_MAXIMUM_POOL_SIZE,
            ),
            connectionTimeoutMs = resolveLong(
                "D_MIGRATE_SERVER_STATE_HIKARI_CONNECTION_TIMEOUT_MS",
                yamlState,
                "hikari.connectionTimeoutMs",
                McpServerStateConfig.DEFAULT_CONNECTION_TIMEOUT_MS,
            ),
            migrationsAuto = resolveBoolean(
                "D_MIGRATE_SERVER_STATE_MIGRATIONS_AUTO",
                yamlState,
                "migrations.auto",
                default = false,
            ),
        )
    }

    private fun loadYamlState(path: Path): Map<String, Any?> {
        if (!Files.isRegularFile(path)) return emptyMap()
        val root = try {
            Files.newInputStream(path).use { input ->
                Load(LoadSettings.builder().build()).loadFromInputStream(input)
            }
        } catch (cause: Throwable) {
            throw McpServerStateConfigError(
                "failed to parse server-state config at $path: ${cause.message ?: cause::class.simpleName}",
                cause,
            )
        } as? Map<*, *> ?: throw McpServerStateConfigError(
            "server-state config at $path: top-level YAML must be a mapping",
        )
        val server = root["server"] as? Map<*, *> ?: return emptyMap()
        val state = server["state"] as? Map<*, *> ?: return emptyMap()
        return flattenState(state, path)
    }

    private fun flattenState(state: Map<*, *>, path: Path): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((keyRaw, value) in state) {
            val key = keyRaw as? String ?: throw McpServerStateConfigError(
                "server.state in $path must use string keys",
            )
            when (key) {
                "hikari", "migrations" -> {
                    val nested = value as? Map<*, *> ?: throw McpServerStateConfigError(
                        "server.state.$key in $path must be a mapping",
                    )
                    for ((nestedKeyRaw, nestedValue) in nested) {
                        val nestedKey = nestedKeyRaw as? String ?: throw McpServerStateConfigError(
                            "server.state.$key in $path must use string keys",
                        )
                        result["$key.$nestedKey"] = nestedValue
                    }
                }
                else -> result[key] = value
            }
        }
        return result
    }

    private fun resolveString(env: String, yaml: Map<String, Any?>, key: String): String? {
        envLookup(env)?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = yaml[key] ?: return null
        val text = raw as? String ?: throw McpServerStateConfigError(
            "server.state.$key must be a string, got ${raw::class.simpleName}",
        )
        return expandSimpleEnvRef(text, "server.state.$key")
    }

    private fun resolveInt(env: String, yaml: Map<String, Any?>, key: String, default: Int): Int {
        val raw = envLookup(env)?.takeIf { it.isNotBlank() } ?: yaml[key] ?: return default
        return when (raw) {
            is Number -> raw.toInt()
            is String -> expandSimpleEnvRef(raw, "server.state.$key").toIntOrNull()
            else -> null
        } ?: throw McpServerStateConfigError("server.state.$key must be an integer")
    }

    private fun resolveLong(env: String, yaml: Map<String, Any?>, key: String, default: Long): Long {
        val raw = envLookup(env)?.takeIf { it.isNotBlank() } ?: yaml[key] ?: return default
        return when (raw) {
            is Number -> raw.toLong()
            is String -> expandSimpleEnvRef(raw, "server.state.$key").toLongOrNull()
            else -> null
        } ?: throw McpServerStateConfigError("server.state.$key must be an integer")
    }

    private fun resolveBoolean(env: String, yaml: Map<String, Any?>, key: String, default: Boolean): Boolean {
        val raw = envLookup(env)?.takeIf { it.isNotBlank() } ?: yaml[key] ?: return default
        return when (raw) {
            is Boolean -> raw
            is String -> when (expandSimpleEnvRef(raw, "server.state.$key").lowercase(Locale.ROOT)) {
                "true", "1", "yes", "y", "on" -> true
                "false", "0", "no", "n", "off" -> false
                else -> null
            }
            else -> null
        } ?: throw McpServerStateConfigError("server.state.$key must be a boolean")
    }

    private fun expandSimpleEnvRef(value: String, field: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("\${") || !trimmed.endsWith("}") || trimmed.count { it == '$' } != 1) {
            return value
        }
        val name = trimmed.substring(2, trimmed.length - 1)
        if (name.isBlank()) {
            throw McpServerStateConfigError("$field contains an empty env reference")
        }
        return envLookup(name) ?: throw McpServerStateConfigError(
            "$field references missing environment variable $name",
        )
    }

    companion object {
        private val STATE_ENV_KEYS = setOf(
            "D_MIGRATE_SERVER_STATE_JDBC_URL",
            "D_MIGRATE_SERVER_STATE_USERNAME",
            "D_MIGRATE_SERVER_STATE_PASSWORD",
            "D_MIGRATE_SERVER_STATE_HIKARI_MAXIMUM_POOL_SIZE",
            "D_MIGRATE_SERVER_STATE_HIKARI_CONNECTION_TIMEOUT_MS",
            "D_MIGRATE_SERVER_STATE_MIGRATIONS_AUTO",
        )
    }
}
