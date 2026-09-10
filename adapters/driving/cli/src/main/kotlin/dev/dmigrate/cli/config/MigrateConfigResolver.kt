package dev.dmigrate.cli.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Werte aus dem `migrate:`-Block der effektiven `.d-migrate.yaml`.
 *
 * `null` heisst: der Schluessel steht nicht in der Datei — dasselbe
 * Nicht-gesetzt wie in [DdlConfig].
 */
internal data class MigrateConfig(
    /**
     * `migrate.raw_sql_sandbox` — ob der Wegwerf-Sandkasten benutzt werden
     * darf, wo keine Herkunft vorliegt.
     *
     * Bewusst **nicht** voreingestellt: er legt auf dem Zielserver ein Schema
     * an (und verwirft es wieder), und dafuer braucht es Rechte, die nicht
     * jeder Migrationsnutzer hat.
     */
    val rawSqlSandbox: Boolean? = null,
)

/** Liest den `migrate:`-Block. Muster und Toleranzen wie [DdlConfigResolver]. */
internal class MigrateConfigResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
    private val preloaded: LoadedConfig? = null,
) {

    fun resolve(): MigrateConfig {
        val (root, path) = preloaded ?: loadEffectiveConfig(configPathFromCli, envLookup, defaultConfigPath)
        val migrate = root?.get("migrate") as? Map<*, *> ?: return MigrateConfig()
        return MigrateConfig(rawSqlSandbox = readBoolean(migrate, "raw_sql_sandbox", path))
    }

    /**
     * Ein fehlerhafter Wert bricht ab, statt still auf `false` zu fallen: wer
     * `raw_sql_sandbox: vielleicht` schreibt, hat eine Absicht, und sie
     * stillschweigend zu verwerfen waere schlimmer als abzubrechen.
     */
    private fun readBoolean(block: Map<*, *>, key: String, source: Path): Boolean? {
        if (!block.containsKey(key)) return null
        return when (val raw = block[key]) {
            is Boolean -> raw
            "true" -> true
            "false" -> false
            else -> throw ConfigResolveException(
                "migrate.$key in $source must be true or false, got: $raw",
            )
        }
    }
}
