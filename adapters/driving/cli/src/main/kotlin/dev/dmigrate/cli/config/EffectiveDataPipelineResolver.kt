package dev.dmigrate.cli.config

import dev.dmigrate.driver.connection.PoolSettings
import java.nio.file.Path

/**
 * Gebündelte effektive Pipeline-Konfiguration (Tuning + Parallelität + Pool) für die
 * Daten-Commands `data export`/`import`/`transfer`.
 *
 * [pool] ist die aus `database.pool:` aufgelöste [PoolSettings] (Config > Default). Sie
 * dient **zwei** Zwecken im selben Ladevorgang: (1) Injektion in `ConnectionConfig.pool`
 * am `poolFactory`-Seam der Wirings, und (2) `pipeline.parallelism: auto` deckelt gegen
 * `pool.maximumPoolSize` statt gegen den `PoolSettings()`-Hardcode-Default.
 */
internal data class EffectiveDataPipeline(
    val tuning: EffectivePipelineTuning,
    val parallelism: ResolvedParallelism,
    val pool: PoolSettings,
)

/**
 * Löst `pipeline.chunk_size`/`fetch_size` **und** `pipeline.parallelism` in **einem**
 * `.d-migrate.yaml`-Ladevorgang auf und mergt jeweils mit den CLI-Flags
 * (Präzedenz CLI-explizit > Config > Default). Früher lud jeder Command den Tuning-
 * und den Parallelism-Resolver getrennt → die Config wurde pro Aufruf zweimal geparst;
 * hier teilen beide den gemeinsamen [loadEffectiveConfig]-Ladevorgang (Review-Fix).
 *
 * [readFetchSize] `false` für `data import`: der Import liest aus Format-Dateien
 * (kein JDBC-`DataReader`), also darf ein für ihn irrelevanter `pipeline.fetch_size`-
 * Config-Fehler den Lauf nicht scheitern lassen; `fetch_size` wird dann nicht gelesen.
 *
 * Die Merge-/Validierungslogik bleibt in [resolveEffectivePipelineTuning]/
 * [resolveEffectiveChunkSize]/[resolveEffectiveParallelism] (nicht dupliziert); diese
 * Funktion reicht ihnen nur den geteilten [LoadedConfig] durch. Wirft
 * [ConfigResolveException] (→ Exit 7) bzw. [IllegalArgumentException] (→ Exit 2) wie sie.
 */
internal fun resolveEffectiveDataPipeline(
    configPath: Path?,
    cliChunkSize: Int?,
    cliFetchSize: Int?,
    cliParallel: Int?,
    readFetchSize: Boolean = true,
    availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    defaultChunkSize: Int = 10_000,
    onNote: (String) -> Unit = {},
): EffectiveDataPipeline {
    val loaded = loadEffectiveConfig(configPath)
    // `database.pool:` im selben Ladevorgang auflösen — die effektive `max_size` deckelt
    // dann `parallelism: auto` (statt des Hardcode-Defaults) und wird zugleich in die
    // ConnectionConfig injiziert (Wiring-Seam). Config > Default; kein CLI-Flag für Pool.
    val pool = resolveEffectivePoolSettings(configPath, preloaded = loaded)
    val tuning = if (readFetchSize) {
        resolveEffectivePipelineTuning(configPath, cliChunkSize, cliFetchSize, defaultChunkSize, preloaded = loaded)
    } else {
        EffectivePipelineTuning(
            chunkSize = resolveEffectiveChunkSize(configPath, cliChunkSize, defaultChunkSize, preloaded = loaded),
            fetchSize = null,
        )
    }
    val parallelism = resolveEffectiveParallelism(
        configPath, cliParallel, availableProcessors, pool.maximumPoolSize, preloaded = loaded, onNote = onNote,
    )
    return EffectiveDataPipeline(tuning, parallelism, pool)
}
