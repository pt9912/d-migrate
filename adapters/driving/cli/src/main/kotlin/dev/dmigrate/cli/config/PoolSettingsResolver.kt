package dev.dmigrate.cli.config

import dev.dmigrate.driver.connection.PoolSettings
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Verdrahtet die `database.pool:`-Sektion aus der effektiven `.d-migrate.yaml` ans
 * Runtime — zuvor spec-dokumentiert (`connection-config-spec.md` §2.2/§3.2), aber auf
 * dem CLI-Datenpfad ein stiller No-op (`ConnectionConfig.pool` war immer der
 * `PoolSettings()`-Default). Gleiche Config-No-op-Familie wie `pipeline.chunk_size`
 * (LN-005) und `pipeline.parallelism`.
 *
 * Gelesen werden die **fünf** im YAML-Schema dokumentierten Keys (`max_size`,
 * `min_idle`, `connection_timeout_ms`, `idle_timeout_ms`, `max_lifetime_ms`). Die drei
 * weiteren [PoolSettings]-Felder (`keepaliveTimeMs`/`statementTimeoutMs`/
 * `networkTimeoutMs`) sind die sicherheitskritischen Cancel-Reaktions-Schranken (s.
 * [PoolSettings]-Doc) und bleiben **bewusst** nicht über diese Sektion user-tunbar.
 * Ungesetzte Keys behalten den jeweiligen `PoolSettings()`-Default.
 *
 * Präzedenz: **Config > Default** (es gibt kein CLI-Flag für Pool-Größen). Werte müssen,
 * falls gesetzt, positive Ganzzahlen sein — sonst [ConfigResolveException] (statt stiller
 * Ignoranz/Coercion, Muster [requirePositiveIntConfig]). Zusätzlich muss `min_idle <=
 * max_size` gelten (statt HikariCPs stiller Coercion).
 */
internal class PoolSettingsResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
    /** Bereits geladene Config (teilt EINEN Ladevorgang mit Tuning/Parallelism). `null` → selbst laden. */
    private val preloaded: LoadedConfig? = null,
) {

    fun resolve(): PoolSettings {
        val (root, path) = preloaded ?: loadEffectiveConfig(configPathFromCli, envLookup, defaultConfigPath)
        val database = root?.get("database") as? Map<*, *> ?: return PoolSettings()
        val pool = database["pool"] as? Map<*, *> ?: return PoolSettings()

        val defaults = PoolSettings()
        val maxSize = readInt(pool, "max_size", path) ?: defaults.maximumPoolSize
        val minIdle = readInt(pool, "min_idle", path) ?: defaults.minimumIdle
        if (minIdle > maxSize) {
            throw ConfigResolveException(
                "database.pool.min_idle ($minIdle) in $path must be <= database.pool.max_size ($maxSize)"
            )
        }
        return PoolSettings(
            maximumPoolSize = maxSize,
            minimumIdle = minIdle,
            connectionTimeoutMs = readLong(pool, "connection_timeout_ms", path) ?: defaults.connectionTimeoutMs,
            idleTimeoutMs = readLong(pool, "idle_timeout_ms", path) ?: defaults.idleTimeoutMs,
            maxLifetimeMs = readLong(pool, "max_lifetime_ms", path) ?: defaults.maxLifetimeMs,
        )
    }

    private fun readInt(pool: Map<*, *>, key: String, source: Path): Int? {
        if (!pool.containsKey(key)) return null
        return requirePositiveIntConfig(pool[key], "database.pool.$key", source)
    }

    private fun readLong(pool: Map<*, *>, key: String, source: Path): Long? {
        if (!pool.containsKey(key)) return null
        return requirePositiveLongConfig(pool[key], "database.pool.$key", source)
    }
}

/**
 * Bequeme Fassade analog [resolveEffectivePipelineTuning]: löst die effektive
 * [PoolSettings] aus `database.pool:` auf (Präzedenz Config > Default). Reicht einen
 * bereits geladenen [LoadedConfig] durch, damit `data export`/`import`/`transfer` den
 * Ladevorgang mit den Pipeline-Resolvern teilen. Wirft [ConfigResolveException] (→ Exit 7).
 */
internal fun resolveEffectivePoolSettings(
    configPath: Path?,
    preloaded: LoadedConfig? = null,
): PoolSettings = PoolSettingsResolver(configPathFromCli = configPath, preloaded = preloaded).resolve()
