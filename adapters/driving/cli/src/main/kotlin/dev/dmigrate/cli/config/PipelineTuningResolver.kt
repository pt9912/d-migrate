package dev.dmigrate.cli.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * LN-005: geparste `pipeline.chunk_size` / `pipeline.fetch_size`-Werte aus der
 * effektiven `.d-migrate.yaml`. `null` bedeutet „im Config nicht gesetzt" — der
 * aufrufende Command mergt dann CLI-explizit > Config > Default.
 */
data class PipelineTuning(
    val chunkSize: Int? = null,
    val fetchSize: Int? = null,
)

/**
 * LN-005: effektive Werte nach dem Merge **CLI-explizit > Config > Default**.
 * [chunkSize] ist immer gesetzt (Default `10_000`); [fetchSize] ist `null`, wenn
 * weder CLI noch Config einen Wert liefern (→ Dialekt-Default am Reader).
 */
data class EffectivePipelineTuning(
    val chunkSize: Int,
    val fetchSize: Int?,
)

/**
 * LN-005: mergt die nullbaren CLI-Flags `--chunk-size`/`--fetch-size` mit den
 * `pipeline.chunk_size`/`pipeline.fetch_size`-Config-Werten und den Defaults.
 * Präzedenz: **CLI-explizit > Config > eingebauter Default**.
 *
 * Wirft [ConfigResolveException] bei Config-Fehlern (→ Command mappt auf Exit 7)
 * und [IllegalArgumentException] bei ungültigen effektiven Werten (→ Exit 2).
 */
fun resolveEffectivePipelineTuning(
    configPath: Path?,
    cliChunkSize: Int?,
    cliFetchSize: Int?,
    defaultChunkSize: Int = 10_000,
): EffectivePipelineTuning {
    val config = PipelineTuningResolver(configPathFromCli = configPath).resolve()
    val chunkSize = cliChunkSize ?: config.chunkSize ?: defaultChunkSize
    require(chunkSize > 0) { "--chunk-size must be > 0, got $chunkSize" }
    val fetchSize = cliFetchSize ?: config.fetchSize
    if (fetchSize != null) {
        require(fetchSize > 0) { "--fetch-size must be > 0, got $fetchSize" }
    }
    return EffectivePipelineTuning(chunkSize = chunkSize, fetchSize = fetchSize)
}

/**
 * LN-005: parst den `pipeline.chunk_size`- und `pipeline.fetch_size`-Wert aus der
 * effektiven `.d-migrate.yaml`. Analog zu [PipelineCheckpointResolver]
 * (`pipeline.checkpoint.*`) — beide Keys lagen zuvor spec-dokumentiert, aber
 * unverdrahtet vor; dieser Resolver hängt sie ans Runtime.
 *
 * Präzedenz im Command: **CLI-explizit > Config > eingebauter Default**
 * (`--chunk-size`/`--fetch-size` sind nullbar; der Default wandert in den Merge).
 *
 * Pfad-Auflösung folgt den gleichen Prioritäten wie andere Config-Abschnitte:
 * `--config` > `D_MIGRATE_CONFIG` > Default.
 *
 * Mappings:
 * - `pipeline.chunk_size` -> [PipelineTuning.chunkSize] (Rows pro Streaming-Chunk)
 * - `pipeline.fetch_size` -> [PipelineTuning.fetchSize] (JDBC-Cursor-Prefetch)
 *
 * Beide müssen, falls gesetzt, positive Ganzzahlen sein — sonst
 * [ConfigResolveException].
 */
class PipelineTuningResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    fun resolve(): PipelineTuning {
        val effective = EffectiveConfigPathResolver(
            configPathFromCli = configPathFromCli,
            envLookup = envLookup,
            defaultConfigPath = defaultConfigPath,
        ).resolve()

        if (!Files.isRegularFile(effective.path)) {
            return when (effective.source) {
                EffectiveConfigSource.DEFAULT -> PipelineTuning()
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

        val root = parsed as? Map<*, *> ?: return PipelineTuning()
        val pipeline = root["pipeline"] as? Map<*, *> ?: return PipelineTuning()

        return PipelineTuning(
            chunkSize = readPositiveInt(pipeline, "chunk_size", effective.path),
            fetchSize = readPositiveInt(pipeline, "fetch_size", effective.path),
        )
    }

    /**
     * Liest einen positiven Int-Wert. Fehlt der Key → `null`. Ist er gesetzt,
     * aber keine positive Ganzzahl → [ConfigResolveException] (lauter Fehler statt
     * stiller Ignoranz — der frühere `pipeline.chunk_size`-No-op war genau das Problem).
     */
    private fun readPositiveInt(pipeline: Map<*, *>, key: String, source: Path): Int? {
        if (!pipeline.containsKey(key)) return null
        val value = pipeline[key] as? Number
            ?: throw ConfigResolveException(
                "pipeline.$key in $source must be a positive integer, got: ${pipeline[key]}"
            )
        val intValue = value.toInt()
        if (intValue <= 0) {
            throw ConfigResolveException(
                "pipeline.$key in $source must be > 0, got $intValue"
            )
        }
        return intValue
    }
}
