package dev.dmigrate.cli.config

import dev.dmigrate.driver.data.OracleEmptyString
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loest die Schreib-Praeferenz fuer den leeren String gegen ein
 * Oracle-Ziel auf: CLI-Flag > Config `write.oracle.empty_string` >
 * konservativer Default (`error`) -- dieselbe Praezedenz wie bei den
 * Lese-Praeferenzen (`dialect-preference-mechanism.md`).
 *
 * Anders als [ReverseAutoincrementResolver] ist diese Aufloesung
 * **streng**: ein vorhandener, aber nicht erkannter Wert ist ein
 * Konfigurationsfehler, kein stiller Rueckfall auf den Default. Der Grund
 * steht in der Spec -- ohne das `literal:`-Praefix waere jeder Tippfehler
 * ein gueltiger Ersatztext und landete unbemerkt in der Spalte. Eine
 * FEHLENDE Deklaration bleibt dagegen unkritisch (Default).
 */
class OracleEmptyStringResolver(
    private val configPathFromCli: Path? = null,
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    /** Geworfen bei einem vorhandenen, aber unlesbaren Wert. Aufrufer → Exit 7. */
    class InvalidPreference(raw: String, source: String) : IllegalArgumentException(
        "Unrecognised value '$raw' for $source. Use `error` (default, changes no data) or " +
            "`${OracleEmptyString.LITERAL_PREFIX}<text>` to substitute that text for an empty string " +
            "in a NOT NULL column.",
    )

    fun resolve(flagValue: String?): OracleEmptyString {
        flagValue?.let {
            return OracleEmptyString.parse(it) ?: throw InvalidPreference(it, "--oracle-empty-string")
        }
        val raw = configValue() ?: return OracleEmptyString.Error
        return OracleEmptyString.parse(raw)
            ?: throw InvalidPreference(raw, "write.oracle.empty_string")
    }

    private fun configValue(): String? {
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
            // Eine kaputte Config meldet sich ueber die Verbindungsaufloesung,
            // nicht hier -- diese Praeferenz ist optional.
            return null
        }
        val root = parsed as? Map<*, *> ?: return null
        val write = root["write"] as? Map<*, *> ?: return null
        val oracle = write["oracle"] as? Map<*, *> ?: return null
        return oracle["empty_string"]?.toString()
    }
}
