package dev.dmigrate.cli.config

import java.nio.file.Path

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
 * Präzedenz: **CLI-explizit > Config > eingebauter Default**. Für `data export`/
 * `transfer` (beide Keys relevant).
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
 * LN-005 (#1a): mergt nur `--chunk-size` mit `pipeline.chunk_size` (CLI > Config >
 * Default) für `data import`. Liest/validiert **bewusst nicht** `pipeline.fetch_size`
 * — der Import nutzt keinen JDBC-`DataReader`, also darf ein (für ihn irrelevanter)
 * `fetch_size`-Config-Fehler den Import nicht scheitern lassen.
 */
fun resolveEffectiveChunkSize(
    configPath: Path?,
    cliChunkSize: Int?,
    defaultChunkSize: Int = 10_000,
): Int {
    val config = PipelineTuningResolver(configPathFromCli = configPath).resolveChunkSizeOnly()
    val chunkSize = cliChunkSize ?: config.chunkSize ?: defaultChunkSize
    require(chunkSize > 0) { "--chunk-size must be > 0, got $chunkSize" }
    return chunkSize
}

/**
 * LN-005: parst `pipeline.chunk_size`/`pipeline.fetch_size` aus der effektiven
 * `.d-migrate.yaml` (gemeinsamer [loadEffectiveConfig]-Loader). Beide Keys lagen
 * zuvor spec-dokumentiert, aber unverdrahtet vor; dieser Resolver hängt sie ans
 * Runtime. Werte müssen, falls gesetzt, positive Ganzzahlen sein — sonst
 * [ConfigResolveException] (statt stiller Ignoranz/Coercion).
 */
class PipelineTuningResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = java.nio.file.Paths.get(".d-migrate.yaml"),
) {

    /** Liest `chunk_size` **und** `fetch_size` (export/transfer). */
    fun resolve(): PipelineTuning = resolveInternal(readFetchSize = true)

    /** Liest nur `chunk_size` (import); `fetch_size` bleibt unberührt/`null`. */
    fun resolveChunkSizeOnly(): PipelineTuning = resolveInternal(readFetchSize = false)

    private fun resolveInternal(readFetchSize: Boolean): PipelineTuning {
        val (root, path) = loadEffectiveConfig(configPathFromCli, envLookup, defaultConfigPath)
        val pipeline = root?.get("pipeline") as? Map<*, *> ?: return PipelineTuning()
        return PipelineTuning(
            chunkSize = readPipelineInt(pipeline, "chunk_size", path),
            fetchSize = if (readFetchSize) readPipelineInt(pipeline, "fetch_size", path) else null,
        )
    }

    private fun readPipelineInt(pipeline: Map<*, *>, key: String, source: Path): Int? {
        if (!pipeline.containsKey(key)) return null
        return requirePositiveIntConfig(pipeline[key], "pipeline.$key", source)
    }
}
