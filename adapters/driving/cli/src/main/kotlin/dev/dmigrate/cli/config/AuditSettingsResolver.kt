package dev.dmigrate.cli.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Aufgelöste `logging.audit`-Einstellungen (LN-027) —
 * [`connection-config-spec.md`](../../../../../../../../spec/connection-config-spec.md).
 */
data class ResolvedAuditSettings(
    val enabled: Boolean,
    val file: Path,
)

/**
 * Liest `logging.audit.enabled` (Default **false** — opt-in) und
 * `logging.audit.file` (Default `.d-migrate/audit.log`) aus der effektiv
 * aufgelösten Config-Datei. Muster analog [I18nSettingsResolver].
 */
class AuditSettingsResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {
    fun resolve(): ResolvedAuditSettings {
        val configPath = EffectiveConfigPathResolver(
            configPathFromCli = configPathFromCli,
            envLookup = envLookup,
            defaultConfigPath = defaultConfigPath,
        ).resolve()
        val audit = loadAuditSection(configPath)
        val enabled = readOptionalBoolean(audit, "enabled", configPath.path) ?: false
        val file = readOptionalString(audit, "file", configPath.path)
            ?.let { Paths.get(it) }
            ?: Paths.get(DEFAULT_AUDIT_FILE)
        return ResolvedAuditSettings(enabled = enabled, file = file)
    }

    private fun loadAuditSection(configPath: EffectiveConfigPath): Map<*, *>? {
        val root = loadRoot(configPath) ?: return null
        val logging = (root["logging"] ?: return null) as? Map<*, *>
            ?: throw ConfigResolveException("logging in ${configPath.path} must be a mapping")
        return (logging["audit"] ?: return null) as? Map<*, *>
            ?: throw ConfigResolveException("logging.audit in ${configPath.path} must be a mapping")
    }

    private fun loadRoot(configPath: EffectiveConfigPath): Map<*, *>? {
        if (!Files.isRegularFile(configPath.path)) {
            return when (configPath.source) {
                EffectiveConfigSource.DEFAULT -> null
                EffectiveConfigSource.CLI ->
                    throw ConfigResolveException("Config file not found: ${configPath.path}")
                EffectiveConfigSource.ENV ->
                    throw ConfigResolveException("D_MIGRATE_CONFIG points to non-existent file: ${configPath.path}")
            }
        }
        val parsed: Any? = try {
            Files.newInputStream(configPath.path).use { input ->
                Load(LoadSettings.builder().build()).loadFromInputStream(input)
            }
        } catch (t: Throwable) {
            throw ConfigResolveException(
                "Failed to parse ${configPath.path}: ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }
        return parsed as? Map<*, *>
            ?: throw ConfigResolveException("Failed to parse ${configPath.path}: top-level YAML must be a mapping")
    }

    private fun readOptionalBoolean(values: Map<*, *>?, key: String, configPath: Path): Boolean? {
        val value = values?.get(key) ?: return null
        return value as? Boolean
            ?: throw ConfigResolveException(
                "logging.audit.$key in $configPath must be a boolean, got ${value::class.simpleName}",
            )
    }

    private fun readOptionalString(values: Map<*, *>?, key: String, configPath: Path): String? {
        val value = values?.get(key) ?: return null
        return value as? String
            ?: throw ConfigResolveException(
                "logging.audit.$key in $configPath must be a string, got ${value::class.simpleName}",
            )
    }

    private companion object {
        private const val DEFAULT_AUDIT_FILE = ".d-migrate/audit.log"
    }
}
