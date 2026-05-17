package dev.dmigrate.cli.config

import dev.dmigrate.driver.EffectiveRoutineCapability
import dev.dmigrate.server.application.routine.RoutineCapabilityConfigParser
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 0.9.7 routine-capability-configurable-source Sub-Slice B: CLI/YAML
 * I/O front-end for the Sub-Slice-A
 * [dev.dmigrate.server.application.routine.RoutineCapabilityConfigParser].
 *
 * The resolver lives in the CLI adapter — analog to
 * [I18nSettingsResolver] — because it owns the operator-facing
 * surfaces (Clikt repeatable flag + SnakeYAML mapping under
 * `routineCapability:` in `.d-migrate.yaml`). [resolve] receives the
 * dialect/server-version `defaults` from the call site (the pipeline
 * has just resolved the live MySQL/MariaDB server version) and
 * delegates the structural validation + precedence merging to the
 * Sub-Slice-A parser.
 *
 * Precedence rule (plan §1): CLI > YAML > `defaults`.
 *
 * Config-path resolution mirrors [I18nSettingsResolver]:
 *
 * - CLI-supplied path (`--config`) wins; if absent, falls through to
 *   `D_MIGRATE_CONFIG` and finally to `.d-migrate.yaml` in cwd.
 * - A missing CLI/ENV path is a hard [ConfigResolveException]; a
 *   missing DEFAULT path is tolerated (= no YAML section).
 * - YAML parse errors and structural violations of the
 *   `routineCapability:` mapping bubble up as [ConfigResolveException].
 *
 * Plan §5 Sub-Slice B explicitly defers the `--routine-capability` CLI
 * flag wiring to the [SchemaMigrateCommand] callsite and the
 * end-to-end renderer pin to Sub-Slice C; this resolver only handles
 * the load + delegate part.
 */
class RoutineCapabilityConfigResolver(
    private val cliFlagValues: List<String>,
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    fun resolve(defaults: EffectiveRoutineCapability.Valid): EffectiveRoutineCapability {
        val yamlSection = loadYamlSection()
        return RoutineCapabilityConfigParser.parse(cliFlagValues, yamlSection, defaults)
    }

    private fun loadYamlSection(): Map<String, Any?>? {
        val configPath = EffectiveConfigPathResolver(
            configPathFromCli = configPathFromCli,
            envLookup = envLookup,
            defaultConfigPath = defaultConfigPath,
        ).resolve()
        if (!Files.isRegularFile(configPath.path)) {
            return when (configPath.source) {
                EffectiveConfigSource.DEFAULT -> null
                EffectiveConfigSource.CLI ->
                    throw ConfigResolveException("Config file not found: ${configPath.path}")
                EffectiveConfigSource.ENV ->
                    throw ConfigResolveException(
                        "D_MIGRATE_CONFIG points to non-existent file: ${configPath.path}",
                    )
            }
        }
        val parsed: Any? = try {
            val settings = LoadSettings.builder().build()
            Files.newInputStream(configPath.path).use { input ->
                Load(settings).loadFromInputStream(input)
            }
        } catch (t: Throwable) {
            throw ConfigResolveException(
                "Failed to parse ${configPath.path}: ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }
        val root = parsed as? Map<*, *>
            ?: throw ConfigResolveException(
                "Failed to parse ${configPath.path}: top-level YAML must be a mapping",
            )
        val sectionRaw = root["routineCapability"] ?: return null
        val section = sectionRaw as? Map<*, *>
            ?: throw ConfigResolveException(
                "routineCapability in ${configPath.path} must be a mapping with keys 'function' / 'procedure'",
            )
        return coerceStringKeyed(section, configPath.path)
    }

    private fun coerceStringKeyed(section: Map<*, *>, sourcePath: Path): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(section.size)
        for ((k, v) in section) {
            val key = k as? String
                ?: throw ConfigResolveException(
                    "routineCapability in $sourcePath has non-string key '$k'",
                )
            out[key] = v
        }
        return out
    }
}
