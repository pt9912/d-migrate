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
 * (`--config` > `D_MIGRATE_CONFIG` > `.d-migrate.yaml`), das Lesen ist aber
 * **bewusst nachsichtig**: eine fehlende, nicht lesbare oder `reverse:`-lose
 * Datei heisst „keine Praeferenz erklaert". Die Praeferenzen sind optional
 * und duerfen einen Reverse nie verhindern — anders als die Verbindungs- und
 * Checkpoint-Aufloesung, die bei einem fehlenden `--config` abbrechen.
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
