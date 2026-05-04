package dev.dmigrate.cli.config

import dev.dmigrate.streaming.CheckpointConfig
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * 0.9.0 Phase C.1 (`docs/ImpPlan-0.9.0-C1.md` §5.2 / §4.4 Ueberplan):
 * parst den `pipeline.checkpoint.*`-Block aus der effektiven
 * `.d-migrate.yaml` und erzeugt daraus eine [CheckpointConfig].
 *
 * Wird vom [DataExportCommand][dev.dmigrate.cli.commands.DataExportCommand]
 * durch eine Callback-Referenz an den
 * [DataExportRunner][dev.dmigrate.cli.commands.DataExportRunner] gereicht,
 * damit der Runner ueber [CheckpointConfig.merge] CLI-Override
 * (`--checkpoint-dir`) und Config-Default zusammenfuehren kann.
 *
 * Pfad-Aufloesung folgt dem Phase-A-Vertrag (gleiche Prioritaeten wie
 * andere Config-Abschnitte): `--config` > `D_MIGRATE_CONFIG` > Default.
 *
 * Mappings (Phase B §4.3):
 *
 * - `pipeline.checkpoint.enabled` -> [CheckpointConfig.enabled]
 * - `pipeline.checkpoint.interval` -> [CheckpointConfig.rowInterval]
 * - `pipeline.checkpoint.max_interval` -> [CheckpointConfig.maxInterval]
 *   (ISO-8601-Duration, z.B. `PT5M`)
 * - `pipeline.checkpoint.directory` -> [CheckpointConfig.directory]
 */
class PipelineCheckpointResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    /**
     * Liefert die geparste [CheckpointConfig] oder `null`, wenn weder
     * Config-Datei noch `pipeline.checkpoint`-Block vorhanden sind.
     */
    fun resolve(): CheckpointConfig? {
        val effective = EffectiveConfigPathResolver(
            configPathFromCli = configPathFromCli,
            envLookup = envLookup,
            defaultConfigPath = defaultConfigPath,
        ).resolve()

        if (!Files.isRegularFile(effective.path)) {
            return when (effective.source) {
                EffectiveConfigSource.DEFAULT -> null
                EffectiveConfigSource.CLI ->
                    throw ConfigResolveException("Config file not found: ${effective.path}")
                EffectiveConfigSource.ENV ->
                    throw ConfigResolveException(
                        "D_MIGRATE_CONFIG points to non-existent file: ${effective.path}"
                    )
            }
        }

        val parsed: Any? = try {
            val settings = LoadSettings.builder().build()
            Files.newInputStream(effective.path).use { input ->
                Load(settings).loadFromInputStream(input)
            }
        } catch (t: Throwable) {
            throw ConfigResolveException(
                "Failed to parse ${effective.path}: ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }

        val root = parsed as? Map<*, *> ?: return null
        val pipeline = root["pipeline"] as? Map<*, *> ?: return null
        val checkpoint = pipeline["checkpoint"] as? Map<*, *> ?: return null

        val enabled = (checkpoint["enabled"] as? Boolean) ?: false
        val interval = (checkpoint["interval"] as? Number)?.toLong()
            ?: CheckpointConfig.DEFAULT_ROW_INTERVAL
        val maxIntervalRaw = checkpoint["max_interval"] as? String
        val maxInterval = maxIntervalRaw?.let { parseDuration(it, effective.path) }
            ?: CheckpointConfig.DEFAULT_MAX_INTERVAL
        val directoryRaw = checkpoint["directory"] as? String
        val directory = directoryRaw?.let(Paths::get)

        return CheckpointConfig(
            enabled = enabled,
            rowInterval = interval,
            maxInterval = maxInterval,
            directory = directory,
        )
    }

    private fun parseDuration(value: String, source: Path): Duration {
        return try {
            Duration.parse(value.trim())
        } catch (_: Exception) {
            throw ConfigResolveException(
                "pipeline.checkpoint.max_interval in $source is not a valid " +
                    "ISO-8601 duration: '$value' (expected e.g. 'PT5M')"
            )
        }
    }
}
