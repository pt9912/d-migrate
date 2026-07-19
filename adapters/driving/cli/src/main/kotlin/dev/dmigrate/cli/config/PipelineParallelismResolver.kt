package dev.dmigrate.cli.config

import dev.dmigrate.driver.connection.PoolSettings
import java.nio.file.Path
import java.nio.file.Paths

/**
 * `pipeline.parallelism`-Config-Wert: `auto` (= CPU-Kerne, gedeckelt auf Pool-Größe)
 * oder eine feste positive Zahl. `null` = nicht gesetzt.
 */
sealed interface ParallelismConfig {
    object Auto : ParallelismConfig
    data class Fixed(val degree: Int) : ParallelismConfig
}

/**
 * Aufgelöste effektive Parallelität nach dem Merge **CLI-explizit > Config > Default(1)**.
 *
 * - [degree]: die konkrete Zahl (auto ist am Resolver-Level bereits aufgelöst; s. Slice-Plan
 *   `docs/planning/in-progress/pipeline-parallelism-config-wiring.md`, „Ansatz A"). Die
 *   SQLite-Klemme greift **danach** unverändert im `ParallelismClamp`.
 * - [fromCli]: war `--parallel` explizit gesetzt? Steuert die Inkompatibilitäts-Regel
 *   (`--resume`/`--atomic`): CLI-explizit → harter Fehler; Config/Default → Fallback auf 1.
 * - [sourceLabel]: herkunftsbewusster Text für Klemm-/Fallback-Meldungen
 *   (`--parallel 8` vs. `pipeline.parallelism: auto (= 8)` vs. `pipeline.parallelism: 8`).
 */
data class ResolvedParallelism(
    val degree: Int,
    val fromCli: Boolean,
    val sourceLabel: String,
)

/**
 * LN-005-Folge: mergt das nullbare CLI-Flag `--parallel` mit `pipeline.parallelism`.
 * Präzedenz: **CLI-explizit > Config > Default 1**. `auto` wird gegen
 * `min(availableProcessors, maxPoolSize)` aufgelöst — die effektive Pool-Größe ist im
 * Datenpfad immer der `PoolSettings()`-Default (code-verifiziert, s. Slice-Plan).
 *
 * Wirft [ConfigResolveException] bei Config-Fehlern (→ Exit 7) und [IllegalArgumentException]
 * bei ungültigem CLI-`--parallel` (→ Exit 2). `availableProcessors`/`maxPoolSize` sind für
 * deterministische Tests injizierbar.
 */
internal fun resolveEffectiveParallelism(
    configPath: Path?,
    cliParallel: Int?,
    availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
    maxPoolSize: Int = PoolSettings().maximumPoolSize,
    preloaded: LoadedConfig? = null,
    onNote: (String) -> Unit = {},
): ResolvedParallelism {
    if (cliParallel != null) {
        require(cliParallel >= 1) { "--parallel must be >= 1, got $cliParallel" }
        // Fix parallel-vs-pool-size-clamp.md: ein explizites `--parallel N` gegen die
        // effektive Pool-Größe deckeln. Jeder Work-Unit hält gleichzeitig JE EINE
        // Source- UND Target-Connection, also braucht Grad N N Connections PRO Pool;
        // N > max_size ließe die überzähligen Worker bis `connectionTimeout` blockieren
        // und dann fail-fast scheitern (Selbst-DoS). Analog zur SQLite-Klemme: deckeln
        // + herkunftsbewusster Hinweis. `auto` deckelt bereits ebenso.
        val clamped = minOf(cliParallel, maxPoolSize).coerceAtLeast(1)
        if (clamped < cliParallel) {
            onNote(
                "--parallel $cliParallel exceeds the connection pool size ($maxPoolSize); " +
                    "clamped to $clamped. Raise database.pool.max_size to parallelize further.",
            )
        }
        return ResolvedParallelism(clamped, fromCli = true, sourceLabel = "--parallel $cliParallel")
    }
    return when (
        val cfg = PipelineParallelismResolver(configPathFromCli = configPath, preloaded = preloaded).resolve()
    ) {
        null -> ResolvedParallelism(degree = 1, fromCli = false, sourceLabel = "pipeline.parallelism")
        ParallelismConfig.Auto -> {
            val degree = minOf(availableProcessors, maxPoolSize).coerceAtLeast(1)
            ResolvedParallelism(degree, fromCli = false, sourceLabel = "pipeline.parallelism: auto (= $degree)")
        }
        is ParallelismConfig.Fixed ->
            ResolvedParallelism(cfg.degree, fromCli = false, sourceLabel = "pipeline.parallelism: ${cfg.degree}")
    }
}

/**
 * Parst `pipeline.parallelism` aus der effektiven `.d-migrate.yaml` (gemeinsamer
 * [loadEffectiveConfig]-Loader). Erlaubt eine **positive Ganzzahl** oder den String **`auto`**
 * (case-insensitive); alles andere → [ConfigResolveException] (laut statt still). Der Key war
 * spec-dokumentiert, aber bislang unverdrahtet (stiller No-op).
 */
internal class PipelineParallelismResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
    /** Bereits geladene Config (teilt EINEN Ladevorgang mit dem Tuning-Resolver). `null` → selbst laden. */
    private val preloaded: LoadedConfig? = null,
) {

    /** `null` = nicht gesetzt; sonst [ParallelismConfig.Auto] oder [ParallelismConfig.Fixed]. */
    fun resolve(): ParallelismConfig? {
        val (root, path) = preloaded ?: loadEffectiveConfig(configPathFromCli, envLookup, defaultConfigPath)
        val pipeline = root?.get("pipeline") as? Map<*, *> ?: return null
        if (!pipeline.containsKey("parallelism")) return null

        val raw = pipeline["parallelism"]
        if (raw is String && raw.trim().equals("auto", ignoreCase = true)) {
            return ParallelismConfig.Auto
        }
        // BigInteger ist selbst ein Number → die Number-Prüfung deckt große YAML-Ganzzahlen mit ab;
        // requirePositiveIntConfig lehnt Fließkomma/Overflow laut ab (keine stille Coercion).
        if (raw is Number) {
            return ParallelismConfig.Fixed(requirePositiveIntConfig(raw, "pipeline.parallelism", path))
        }
        throw ConfigResolveException(
            "pipeline.parallelism in $path must be a positive integer or 'auto', got: $raw"
        )
    }
}
