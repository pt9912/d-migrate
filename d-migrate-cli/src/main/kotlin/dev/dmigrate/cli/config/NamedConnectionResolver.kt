package dev.dmigrate.cli.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Minimaler Loader für `.d-migrate.yaml` (Plan §6.14).
 *
 * Implementiert nur die Subset-Funktionalität, die `data export` in 0.3.0
 * tatsächlich braucht — vgl. `connection-config-spec.md` für die vollständige
 * Ziel-Spezifikation, die in 0.4.0+ erweitert wird.
 *
 * **Was 0.3.0 macht**:
 *
 * - respektiert `--config`/`-c` (CLI) und `D_MIGRATE_CONFIG` (ENV) — mit
 *   Priorität CLI > ENV > `./.d-migrate.yaml` (Default, optional)
 * - löst `database.connections.<name>` zu einer URL-String auf
 * - substituiert `${ENV_VAR}` in den URL-Werten (mit `$${VAR}`-Escape)
 * - liest `database.default_source`, ignoriert es aber für `data export`
 *   (in 0.4.0+ relevant für `data import`)
 *
 * **Was 0.3.0 NICHT macht** (siehe §6.14.2):
 *
 * - Pool-Settings (`database.pool.*`) — Hikari-Defaults werden verwendet
 * - Profile, mehrere Connections pro Umgebung
 * - Granulare Validierungs-Codes E400–E405 — nur generischer Exit 7
 * - Encrypted credentials — kommt in 1.0.0
 *
 * Alle Fehlerpfade werfen [ConfigResolveException] mit der Meldung aus
 * §6.14.3, die der CLI-Aufrufer auf Exit-Code 7 mappt.
 */
class NamedConnectionResolver(
    /** CLI-Wert (`--config`/`-c`) — gewinnt vor ENV. */
    private val configPathFromCli: Path? = null,
    /** Erlaubt Test-Override; Default liest `D_MIGRATE_CONFIG` aus dem Environment. */
    private val envLookup: (String) -> String? = System::getenv,
    /** Erlaubt Test-Override für das CWD beim Default-Lookup `./.d-migrate.yaml`. */
    private val defaultConfigPath: Path = Paths.get(".d-migrate.yaml"),
) {

    /**
     * Löst `--source <value>` zu einer vollständigen Connection-URL auf.
     *
     * - Wenn `source` ein `://` enthält, wird er **unverändert** zurückgegeben
     *   und die Config-Datei wird nicht gelesen — auch nicht wenn `--config`
     *   gesetzt ist (das ist intentional, sodass URL-Direktnutzung jederzeit
     *   funktioniert).
     * - Sonst wird die Config geladen und `database.connections.<source>`
     *   nachgeschlagen, dann `${ENV_VAR}`-substituiert.
     *
     * @throws ConfigResolveException bei jedem Fehlerpfad mit der §6.14.3-
     *   Meldung; CLI mappt das auf Exit-Code 7.
     */
    fun resolve(source: String): String {
        require(source.isNotBlank()) { "--source must not be blank" }

        // Direkte URL — Config-Pfad ist irrelevant.
        if (source.contains("://")) return source

        // Sonst: Config-Pfad nach Priorität CLI > ENV > Default ermitteln.
        val (configPath, isExplicit) = resolveConfigPath()

        if (!Files.isRegularFile(configPath)) {
            // Datei fehlt → Meldung hängt davon ab, ob CLI/ENV explizit war.
            throw when {
                isExplicit && configPathFromCli != null ->
                    ConfigResolveException("Config file not found: $configPath")
                isExplicit ->
                    ConfigResolveException("D_MIGRATE_CONFIG points to non-existent file: $configPath")
                else ->
                    ConfigResolveException(
                        "'--source $source' looks like a connection name, but no .d-migrate.yaml " +
                            "was found. Use --config <path>, set D_MIGRATE_CONFIG, or pass a full URL."
                    )
            }
        }

        val rawUrl = lookupConnectionUrl(configPath, source)
        return substituteEnvVars(rawUrl, source)
    }

    /**
     * Ermittelt den effektiven Config-Pfad nach Priorität CLI > ENV > Default
     * sowie ein Flag, ob die Quelle explizit gesetzt war (für Fehlermeldungen).
     */
    private fun resolveConfigPath(): Pair<Path, Boolean> {
        configPathFromCli?.let { return it to true }
        envLookup("D_MIGRATE_CONFIG")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) to true }
        return defaultConfigPath to false
    }

    /**
     * Liest die Config-Datei mit SnakeYAML Engine und löst
     * `database.connections.<name>` auf. Die Datei wird hier zum ersten Mal
     * geöffnet, damit `${ENV_VAR}`-Fehler mit dem Connection-Namen
     * attribuiert werden können.
     */
    @Suppress("UNCHECKED_CAST")
    private fun lookupConnectionUrl(configPath: Path, name: String): String {
        val parsed: Any? = try {
            val settings = LoadSettings.builder().build()
            Files.newInputStream(configPath).use { input ->
                Load(settings).loadFromInputStream(input)
            }
        } catch (t: Throwable) {
            throw ConfigResolveException(
                "Failed to parse $configPath: ${t.message ?: t::class.simpleName}",
                cause = t,
            )
        }

        val root = parsed as? Map<String, Any?>
            ?: throw ConfigResolveException("Failed to parse $configPath: top-level YAML must be a mapping")

        val database = root["database"] as? Map<String, Any?>
            ?: throw ConfigResolveException(
                "Connection name '$name' is not defined in $configPath under database.connections."
            )

        val connections = database["connections"] as? Map<String, Any?>
            ?: throw ConfigResolveException(
                "Connection name '$name' is not defined in $configPath under database.connections."
            )

        val raw = connections[name]
            ?: throw ConfigResolveException(
                "Connection name '$name' is not defined in $configPath under database.connections."
            )

        return when (raw) {
            is String -> raw
            else -> throw ConfigResolveException(
                "Connection '$name' in $configPath must be a string, got ${raw::class.simpleName}"
            )
        }
    }

    /**
     * Ersetzt `${VAR}` durch `System.getenv(VAR)` (bzw. die Test-`envLookup`).
     * `$${VAR}` bleibt als literales `${VAR}` stehen (Escape gemäß
     * connection-config-spec.md §3.3).
     *
     * Werte werden literal substituiert — kein Auto-URL-Encoding (Plan §6.14
     * F13). Sonderzeichen in Passwörtern müssen vom Nutzer vorab encoded
     * werden.
     */
    private fun substituteEnvVars(value: String, connectionName: String): String {
        // Erst die `$${VAR}`-Escapes durch ein Sentinel ersetzen, damit der
        // Haupt-Regex sie nicht aufgreift. Wir nutzen `\u0000` als Sentinel,
        // weil Connection-URLs garantiert keinen NUL-Byte enthalten dürfen.
        val sentinel = "\u0000"
        val withEscapesHidden = ESCAPED_VAR.replace(value) { match ->
            sentinel + match.groupValues[1] + sentinel
        }

        val substituted = ENV_VAR.replace(withEscapesHidden) { match ->
            val varName = match.groupValues[1]
            envLookup(varName) ?: throw ConfigResolveException(
                "Environment variable '$varName' (referenced by connection '$connectionName') is not set."
            )
        }

        // Sentinel zurückübersetzen zu literalem `${VAR}`
        return ESCAPED_RESTORE.replace(substituted) { match ->
            "\${" + match.groupValues[1] + "}"
        }
    }

    companion object {
        /** Matcht `${VAR_NAME}` (NICHT `$${VAR_NAME}`). */
        private val ENV_VAR = Regex("""\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")

        /** Matcht `$${VAR_NAME}` zur Vor-Maskierung. */
        private val ESCAPED_VAR = Regex("""\$\$\{([A-Za-z_][A-Za-z0-9_]*)\}""")

        /** Matcht das Sentinel-umschlossene Escape, um es zurück zu rekonstruieren. */
        private val ESCAPED_RESTORE = Regex("""\u0000([A-Za-z_][A-Za-z0-9_]*)\u0000""")
    }
}

/**
 * Wird vom [NamedConnectionResolver] geworfen, wenn die Config-Auflösung
 * fehlschlägt. Der CLI-Aufrufer mappt das auf Exit-Code 7 (§6.10).
 */
class ConfigResolveException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
