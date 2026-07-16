package dev.dmigrate.cli.config

import dev.dmigrate.streaming.CheckpointConfig
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration

/**
 * LF-013 / LN-012: parst den `pipeline.checkpoint.*`-Block aus der effektiven
 * `.d-migrate.yaml` und erzeugt daraus eine [CheckpointConfig].
 *
 * Wird vom [DataExportCommand][dev.dmigrate.cli.commands.DataExportCommand]
 * durch eine Callback-Referenz an den
 * [DataExportRunner][dev.dmigrate.cli.commands.DataExportRunner] gereicht,
 * damit der Runner ueber [CheckpointConfig.merge] CLI-Override
 * (`--checkpoint-dir`) und Config-Default zusammenfuehren kann.
 *
 * Pfad-/Lade-Boilerplate liegt seit LN-005 zentral in [loadEffectiveConfig]
 * (`--config` > `D_MIGRATE_CONFIG` > Default).
 *
 * Mappings:
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
        val (root, source) = loadEffectiveConfig(configPathFromCli, envLookup, defaultConfigPath)
        val pipeline = root?.get("pipeline") as? Map<*, *> ?: return null
        val checkpoint = pipeline["checkpoint"] as? Map<*, *> ?: return null

        val enabled = (checkpoint["enabled"] as? Boolean) ?: false
        val interval = (checkpoint["interval"] as? Number)?.toLong()
            ?: CheckpointConfig.DEFAULT_ROW_INTERVAL
        val maxIntervalRaw = checkpoint["max_interval"] as? String
        val maxInterval = maxIntervalRaw?.let { parseDuration(it, source) }
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
