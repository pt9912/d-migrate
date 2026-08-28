package dev.dmigrate.cli.config

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Werte aus dem `ddl:`-Block der effektiven `.d-migrate.yaml`.
 *
 * `null` heisst durchgaengig: der Schluessel steht nicht in der Datei. Was dann
 * gilt, entscheidet der Merge mit den CLI-Flags, nicht dieser Resolver — wer
 * hier schon Defaults einsetzte, koennte „nicht gesetzt" nicht mehr von „auf
 * den Default gesetzt" unterscheiden.
 */
internal data class DdlConfig(
    /** `ddl.mssql.partition_storage` — Filegroup, auf der partitionierte Daten liegen. */
    val mssqlPartitionStorage: String? = null,
)

/**
 * Liest den `ddl:`-Block der Konfiguration.
 *
 * Die Spec beschreibt den Block breiter, als er hier gelesen wird — sie ist das
 * Zielbild, und Zielbild und Ist-Zustand duerfen auseinanderfallen. Gelesen
 * wird, wofuer es einen Konsumenten gibt: ein Schluessel ohne Wirkung waere
 * schlimmer als gar keiner, weil er eine Einstellung verspricht, die nichts tut.
 *
 * Unbekannte Schluessel im Block bleiben folgenlos (dieselbe Toleranz wie bei
 * `pipeline:` und `database.pool:`); fehlerhafte Werte bekannter Schluessel
 * dagegen brechen ab, statt still auf den Default zu fallen.
 */
internal class DdlConfigResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
    /** Bereits geladene Config; `null` → selbst laden (Muster [PipelineTuningResolver]). */
    private val preloaded: LoadedConfig? = null,
) {

    fun resolve(): DdlConfig {
        val (root, path) = preloaded ?: loadEffectiveConfig(configPathFromCli, envLookup, defaultConfigPath)
        val ddl = root?.get("ddl") as? Map<*, *> ?: return DdlConfig()
        val mssql = ddl["mssql"] as? Map<*, *> ?: return DdlConfig()
        return DdlConfig(
            mssqlPartitionStorage = readIdentifier(mssql, "partition_storage", path),
        )
    }

    /**
     * Ein Filegroup-Name geht unquotiert in `CREATE PARTITION SCHEME`. Er wird
     * deshalb hier geprueft und nicht erst dort: leer oder mit Sonderzeichen ist
     * er kein Bezeichner, sondern eine Moeglichkeit, fremdes DDL einzuschleusen.
     */
    private fun readIdentifier(block: Map<*, *>, key: String, source: Path): String? {
        if (!block.containsKey(key)) return null
        val raw = block[key]
        val value = (raw as? String)?.trim()
        if (value.isNullOrEmpty()) {
            throw ConfigResolveException("ddl.mssql.$key in $source must be a non-empty string, got: $raw")
        }
        if (!value.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            throw ConfigResolveException(
                "ddl.mssql.$key in $source must be a plain identifier " +
                    "(letters, digits, '_', '-'), got: $value",
            )
        }
        return value
    }
}

/**
 * Der effektive Ablageort nach dem Merge **CLI-explizit > Config > Default** —
 * dieselbe Praezedenz wie bei den `pipeline:`-Werten.
 */
internal fun resolveEffectivePartitionStorage(
    configPath: Path?,
    cliValue: String?,
    defaultStorage: String = "PRIMARY",
    preloaded: LoadedConfig? = null,
): String {
    val config = DdlConfigResolver(configPathFromCli = configPath, preloaded = preloaded).resolve()
    return cliValue ?: config.mssqlPartitionStorage ?: defaultStorage
}
