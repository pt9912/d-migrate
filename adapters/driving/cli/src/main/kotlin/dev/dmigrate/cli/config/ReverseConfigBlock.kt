package dev.dmigrate.cli.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Der `reverse:`-Block der wirksamen Konfiguration
 * (`spec/connection-config-spec.md`), gelesen fuer die Reverse-Praeferenzen.
 *
 * Der Pfad folgt derselben Reihenfolge wie die uebrigen Abschnitte
 * (`--config` > `D_MIGRATE_CONFIG` > `.d-migrate.yaml`), das Lesen ist
 * **nachsichtig, wo nichts erklaert ist**: eine fehlende, nicht lesbare oder
 * `reverse:`-lose Datei heisst „keine Praeferenz erklaert" — anders als die
 * Verbindungs- und Checkpoint-Aufloesung, die bei einem fehlenden `--config`
 * abbrechen. **Ein vorhandener, aber nicht erkannter Wert** ist dagegen ein
 * Konfigurationsfehler ([InvalidReversePreference]), wie bei den
 * Schreib-Praeferenzen (`spec/dialect-preference-mechanism.md`): ein
 * Tippfehler (`identiy`) fiele sonst still auf den Default zurueck.
 */
internal class ReverseConfigBlock(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    /** Der Unterblock `reverse.<dialect>`, oder `null`, wenn es ihn nicht gibt. */
    fun dialect(dialect: String): Map<*, *>? {
        val effective = EffectiveConfigPathResolver(
            configPathFromCli = configPathFromCli,
            envLookup = envLookup,
            defaultConfigPath = defaultConfigPath,
        ).resolve()
        if (!Files.isRegularFile(effective.path)) return null
        val parsed: Any? = try {
            val settings = LoadSettings.builder().build()
            Files.newInputStream(effective.path).use { input -> Load(settings).loadFromInputStream(input) }
        } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
            // Optionale Praeferenz: eine kaputte Konfiguration meldet die
            // Verbindungsaufloesung, nicht diese Stelle.
            return null
        }
        val root = parsed as? Map<*, *> ?: return null
        val reverse = root["reverse"] as? Map<*, *> ?: return null
        return reverse[dialect] as? Map<*, *>
    }
}

/**
 * Ein vorhandener, aber nicht erkannter Wert einer Lese-Praeferenz. Aufrufer:
 * CLI → Exit 7, `mcp serve` → Startfehler.
 *
 * @param key der Konfigurationsschluessel, etwa `reverse.mysql.autoincrement_syntax`
 * @param raw der gelesene Wert
 * @param allowed die zulaessigen Werte, der Default zuerst
 */
class InvalidReversePreference(key: String, raw: Any?, allowed: List<String>) : IllegalArgumentException(
    "Unrecognised value '$raw' for $key. Use ${allowed.joinToString(" or ") { "`$it`" }} " +
        "(default `${allowed.first()}`).",
)
