package dev.dmigrate.cli.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * LN-005 (R2): parst `export.parquet.row_group_bytes` aus der effektiven
 * `.d-migrate.yaml`. Steuert die Parquet-Row-Group-Größe des `data export`-Pfads
 * (Heap-Budget beim parallelen Parquet-Export); ohne Config-Wert greift der
 * eingebaute Default (32 MiB, `ParquetChunkWriter.DEFAULT_ROW_GROUP_BYTES`).
 *
 * Analog zu [PipelineCheckpointResolver] / [PipelineTuningResolver]; ein
 * Format-/Export-Detail liegt unter `export.parquet.*`, nicht unter `pipeline.*`.
 *
 * Pfad-Auflösung: `--config` > `D_MIGRATE_CONFIG` > Default. Ein gesetzter, aber
 * nicht-positiver/nicht-numerischer Wert ergibt eine [ConfigResolveException].
 */
class ParquetExportConfigResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    /** `export.parquet.row_group_bytes` (positive Ganzzahl) oder `null`, wenn nicht gesetzt. */
    fun resolveRowGroupBytes(): Long? {
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
        val export = root["export"] as? Map<*, *> ?: return null
        val parquet = export["parquet"] as? Map<*, *> ?: return null
        if (!parquet.containsKey("row_group_bytes")) return null

        val value = parquet["row_group_bytes"] as? Number
            ?: throw ConfigResolveException(
                "export.parquet.row_group_bytes in ${effective.path} must be a positive integer, " +
                    "got: ${parquet["row_group_bytes"]}"
            )
        val bytes = value.toLong()
        if (bytes <= 0L) {
            throw ConfigResolveException(
                "export.parquet.row_group_bytes in ${effective.path} must be > 0, got $bytes"
            )
        }
        return bytes
    }
}
