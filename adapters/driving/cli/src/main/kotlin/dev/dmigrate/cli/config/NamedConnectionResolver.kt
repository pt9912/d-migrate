package dev.dmigrate.cli.config

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Loader für `.d-migrate.yaml` (Plan §6.14 / §3.7.3).
 *
 * **Resolve-Funktionen**:
 *
 * | Methode | Zweck | Default-Key |
 * |---|---|---|
 * | [resolve] | 0.3.0-Kompatibilität: löst `--source` als Pflichtangabe auf | — |
 * | [resolveSource] | `data export`: nullable `--source`, Default aus `database.default_source` | `default_source` |
 * | [resolveTarget] | `data import`: nullable `--target`, Default aus `database.default_target` | `default_target` |
 *
 * **Resolve-Tabelle** (§3.7.3):
 * 1. Wert ist gesetzt und enthält `://` → direkte URL, Config wird nicht gelesen.
 * 2. Wert ist gesetzt, kein `://` → Connection-Name aus `database.connections`.
 * 3. Wert ist `null` → Default aus Config (`database.default_source` / `database.default_target`).
 *    Default ist URL → zurückgeben. Default ist Name → weiter mit (2).
 *    Default fehlt → [ConfigResolveException] (Exit 7) bzw. CLI-Fehler (Exit 2).
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
        val configPath = resolveConfigPath()

        if (!Files.isRegularFile(configPath.path)) {
            // Datei fehlt → Meldung hängt davon ab, ob CLI/ENV explizit war.
            throw when (configPath.source) {
                EffectiveConfigSource.CLI ->
                    ConfigResolveException("Config file not found: ${configPath.path}")
                EffectiveConfigSource.ENV ->
                    ConfigResolveException("D_MIGRATE_CONFIG points to non-existent file: ${configPath.path}")
                EffectiveConfigSource.DEFAULT ->
                    ConfigResolveException(
                        "'--source $source' looks like a connection name, but no .d-migrate.yaml " +
                            "was found. Use --config <path>, set D_MIGRATE_CONFIG, or pass a full URL."
                    )
            }
        }

        val rawUrl = lookupConnectionUrl(configPath.path, source)
        return substituteEnvVars(rawUrl, source)
    }

    /**
     * Löst `--source <value>` für `data export` auf (nullable).
     *
     * Wenn [source] `null` ist, wird `database.default_source` aus der
     * Config als Fallback verwendet. Enthält der Default `://`, wird er
     * als direkte URL interpretiert; sonst als Connection-Name aus
     * `database.connections`.
     *
     * @throws ConfigResolveException wenn kein Default konfiguriert ist
     *   und [source] `null` war, oder bei jedem §6.14.3-Fehlerpfad.
     */
    fun resolveSource(source: String?): String {
        if (source != null) return resolve(source)
        return resolveDefault("default_source", "--source")
    }

    /**
     * Löst `--target <value>` für `data import` auf (nullable).
     *
     * Wenn [target] `null` ist, wird `database.default_target` aus der
     * Config als Fallback verwendet. Enthält der Default `://`, wird er
     * als direkte URL interpretiert; sonst als Connection-Name aus
     * `database.connections`.
     *
     * @throws ConfigResolveException wenn kein Default konfiguriert ist
     *   und [target] `null` war, oder bei jedem §6.14.3-Fehlerpfad.
     */
    fun resolveTarget(target: String?): String {
        if (target != null) return resolve(target)
        return resolveDefault("default_target", "--target")
    }

    /**
     * Liest `database.<defaultKey>` aus der Config und löst den Wert als
     * URL oder Connection-Name auf.
     */
    private fun resolveDefault(defaultKey: String, cliFlag: String): String {
        val configPath = resolveConfigPath()

        if (!Files.isRegularFile(configPath.path)) {
            throw when (configPath.source) {
                EffectiveConfigSource.CLI ->
                    ConfigResolveException("Config file not found: ${configPath.path}")
                EffectiveConfigSource.ENV ->
                    ConfigResolveException("D_MIGRATE_CONFIG points to non-existent file: ${configPath.path}")
                EffectiveConfigSource.DEFAULT ->
                    ConfigMissingDefaultException(
                        "$cliFlag was not provided and no .d-migrate.yaml was found to look up " +
                            "database.$defaultKey. Use $cliFlag <value>, --config <path>, " +
                            "set D_MIGRATE_CONFIG, or pass a full URL."
                    )
            }
        }

        val defaultValue = lookupDefaultValue(configPath.path, defaultKey)
            ?: throw ConfigMissingDefaultException(
                "$cliFlag was not provided and database.$defaultKey is not set in ${configPath.path}."
            )

        // Default ist eine direkte URL?
        if (defaultValue.contains("://")) {
            return substituteEnvVars(defaultValue, defaultKey)
        }

        // Default ist ein Connection-Name → über database.connections auflösen.
        val rawUrl = lookupConnectionUrl(configPath.path, defaultValue)
        return substituteEnvVars(rawUrl, defaultValue)
    }

    /**
     * Liest `database.<key>` aus der bereits geparsten Config-Datei.
     * Gibt `null` zurück wenn der Key nicht gesetzt ist.
     */
    @Suppress("UNCHECKED_CAST")
    private fun lookupDefaultValue(configPath: Path, key: String): String? {
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

        val database = root["database"] as? Map<String, Any?> ?: return null
        val value = database[key] ?: return null

        return when (value) {
            is String -> value
            else -> throw ConfigResolveException(
                "database.$key in $configPath must be a string, got ${value::class.simpleName}"
            )
        }
    }

    /**
     * Ermittelt den effektiven Config-Pfad nach Priorität CLI > ENV > Default
     * sowie ein Flag, ob die Quelle explizit gesetzt war (für Fehlermeldungen).
     */
    private fun resolveConfigPath(): EffectiveConfigPath =
        EffectiveConfigPathResolver(
            configPathFromCli = configPathFromCli,
            envLookup = envLookup,
            defaultConfigPath = defaultConfigPath,
        ).resolve()

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
open class ConfigResolveException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Spezialisierung: weder CLI-Flag noch Config-Default waren gesetzt.
 * Erlaubt dem Aufrufer, diesen Fall per Typ (Exit 2) vom generischen
 * Auflösungsfehler (Exit 7) zu unterscheiden.
 */
class ConfigMissingDefaultException(message: String, cause: Throwable? = null) : ConfigResolveException(message, cause)
