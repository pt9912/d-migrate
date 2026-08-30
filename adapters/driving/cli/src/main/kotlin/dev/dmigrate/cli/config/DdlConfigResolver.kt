package dev.dmigrate.cli.config

import dev.dmigrate.driver.MysqlTableOptions
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
    /** `ddl.mssql.hash_partitions` — `action_required` oder `computed_column`. */
    val mssqlHashPartitions: String? = null,
    /** `ddl.mysql.engine` — Storage Engine der erzeugten Tabellen. */
    val mysqlEngine: String? = null,
    /** `ddl.mysql.charset` — Standard-Zeichensatz der erzeugten Tabellen. */
    val mysqlCharset: String? = null,
    /** `ddl.mysql.collation` — Standard-Kollation der erzeugten Tabellen. */
    val mysqlCollation: String? = null,
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
        // Jeder Dialektblock wird fuer sich gelesen: ein fehlender darf die
        // anderen nicht mitnehmen.
        val mssql = ddl["mssql"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val mysql = ddl["mysql"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        return DdlConfig(
            mssqlPartitionStorage = readIdentifier(mssql, "partition_storage", path),
            mssqlHashPartitions = readChoice(
                mssql, "hash_partitions", path, setOf("action_required", "computed_column"),
            ),
            // Engine, Zeichensatz und Kollation gehen unquotiert in die
            // Tabellen-Optionen von `CREATE TABLE` — dieselbe Pruefung wie beim
            // Filegroup-Namen, aus demselben Grund.
            mysqlEngine = readIdentifier(mysql, "engine", path),
            mysqlCharset = readIdentifier(mysql, "charset", path),
            mysqlCollation = readIdentifier(mysql, "collation", path),
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

    /**
     * Ein Wert aus einer festen Auswahl. Anders als beim CLI-Flag, wo Clikt die
     * Auswahl erzwingt, kommt hier beliebiger Text an — ein Tippfehler darf
     * nicht stillschweigend auf den Default fallen, sonst glaubte der Anwender,
     * die Emulation sei eingeschaltet.
     */
    private fun readChoice(block: Map<*, *>, key: String, source: Path, allowed: Set<String>): String? {
        if (!block.containsKey(key)) return null
        val value = (block[key] as? String)?.trim()?.lowercase()
        if (value == null || value !in allowed) {
            throw ConfigResolveException(
                "ddl.mssql.$key in $source must be one of ${allowed.sorted().joinToString(", ")}, " +
                    "got: ${block[key]}",
            )
        }
        return value
    }
}

/**
 * Der effektive HASH-Modus nach dem Merge **CLI-explizit > Config > Default**.
 *
 * Der Rueckgabewert bleibt die rohe Zeichenkette: die Abbildung auf
 * `MssqlHashPartitionMode` macht der Runner, damit ein unbekannter CLI-Wert
 * dieselbe Meldung bekommt wie bisher.
 */
internal fun resolveEffectiveHashPartitions(
    configPath: Path?,
    cliValue: String?,
    preloaded: LoadedConfig? = null,
): String? {
    val config = DdlConfigResolver(configPathFromCli = configPath, preloaded = preloaded).resolve()
    return cliValue ?: config.mssqlHashPartitions
}

/**
 * Die effektiven MySQL-Tabellen-Optionen: was die Konfiguration nennt, sonst
 * die Vorgabe. Ein CLI-Flag gibt es fuer diese drei nicht — sie beschreiben das
 * Ziel, nicht den einzelnen Aufruf.
 */
internal fun resolveEffectiveMysqlTableOptions(
    configPath: Path?,
    preloaded: LoadedConfig? = null,
): MysqlTableOptions {
    val config = DdlConfigResolver(configPathFromCli = configPath, preloaded = preloaded).resolve()
    val defaults = MysqlTableOptions()
    return MysqlTableOptions(
        engine = config.mysqlEngine ?: defaults.engine,
        charset = config.mysqlCharset ?: defaults.charset,
        collation = config.mysqlCollation ?: defaults.collation,
    )
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
