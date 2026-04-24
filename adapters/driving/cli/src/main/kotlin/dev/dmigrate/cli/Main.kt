package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.I18nSettingsResolver
import dev.dmigrate.cli.config.UnsupportedLanguageException
import dev.dmigrate.cli.i18n.ResolvedI18nSettings
import dev.dmigrate.cli.i18n.UnicodeNormalizationMode
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.ExportCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.driver.DatabaseDriverRegistry
import java.nio.file.Path
import java.time.ZoneId
import java.util.Locale
import java.util.Properties

data class CliContext(
    val outputFormat: String = "plain",
    val verbose: Boolean = false,
    val quiet: Boolean = false,
    val noColor: Boolean = false,
    val noProgress: Boolean = false,
    val locale: Locale = ResolvedI18nSettings.DEFAULT.locale,
    val timezone: ZoneId = ResolvedI18nSettings.DEFAULT.timezone,
    val normalization: UnicodeNormalizationMode = ResolvedI18nSettings.DEFAULT.normalization,
)

private const val VERSION_RESOURCE = "dmigrate-version.properties"
private const val VERSION_KEY = "version"
private const val UNKNOWN_VERSION = "unknown"

internal fun cliVersion(): String {
    val properties = Properties()
    val version = DMigrate::class.java.classLoader
        .getResourceAsStream(VERSION_RESOURCE)
        ?.use { input ->
            properties.load(input)
            properties.getProperty(VERSION_KEY)?.trim()
        }
        ?.takeUnless { it.isNullOrBlank() || '$' in it || '{' in it || '}' in it }
    return version ?: UNKNOWN_VERSION
}

class DMigrate(
    private val envLookup: (String) -> String? = System::getenv,
    private val defaultConfigPath: Path = Path.of(".d-migrate.yaml"),
    private val systemLocaleProvider: () -> Locale = Locale::getDefault,
    private val systemTimezoneProvider: () -> ZoneId = ZoneId::systemDefault,
) : CliktCommand(name = "d-migrate") {
    override fun help(context: Context) = "Database-agnostic migration and data management framework"

    val config by option("--config", "-c", help = "Path to configuration file").path()
    val lang by option(
        "--lang",
        help = "Language for human-readable CLI output (de, en). " +
            "Supersedes D_MIGRATE_LANG, LC_ALL/LANG and i18n.default_locale. " +
            "Invalid or unsupported values exit with 2.",
    )
    val outputFormat by option("--output-format", help = "Output format: plain, json, yaml")
        .choice("plain", "json", "yaml").default("plain")
    val verbose by option("--verbose", "-v", help = "Verbose output (DEBUG level)").flag()
    val quiet by option("--quiet", "-q", help = "Only show errors").flag()
    val noColor by option("--no-color", help = "Disable colored output").flag()
    val noProgress by option("--no-progress", help = "Disable progress display").flag()
    val yes by option("--yes", "-y", help = "Accept confirmations automatically").flag()

    init {
        versionOption(cliVersion())
    }

    private var cachedCliContext: CliContext? = null

    override fun run() {
        if (verbose && quiet) {
            throw UsageError("--verbose and --quiet are mutually exclusive")
        }
        cachedCliContext = resolveCliContext()
    }

    fun cliContext(): CliContext =
        cachedCliContext ?: resolveCliContext().also { cachedCliContext = it }

    private fun resolveCliContext(): CliContext {
        val i18n = try {
            I18nSettingsResolver(
                configPathFromCli = config,
                envLookup = envLookup,
                defaultConfigPath = defaultConfigPath,
                systemLocaleProvider = systemLocaleProvider,
                systemTimezoneProvider = systemTimezoneProvider,
                langFromCli = lang,
            ).resolve()
        } catch (e: UnsupportedLanguageException) {
            // 0.9.0 Phase A §4.5: unsupported explicit --lang ist ein
            // lokaler CLI-Validierungsfehler (Exit 2), kein Config-/
            // Checkpoint-Problem.
            printLocalError(e.message ?: "Unsupported --lang value", "--lang")
            throw ProgramResult(2)
        } catch (e: ConfigResolveException) {
            printLocalError(e.message ?: "Failed to resolve i18n settings", config?.toString() ?: "i18n")
            throw ProgramResult(7)
        }

        return CliContext(
            outputFormat = outputFormat,
            verbose = verbose,
            quiet = quiet,
            noColor = noColor,
            noProgress = noProgress,
            locale = i18n.locale,
            timezone = i18n.timezone,
            normalization = i18n.normalization,
        )
    }

    private fun printLocalError(message: String, source: String) {
        OutputFormatter(
            CliContext(
                outputFormat = outputFormat,
                verbose = verbose,
                quiet = quiet,
                noColor = noColor,
                noProgress = noProgress,
            )
        ).printError(message, source)
    }
}

/**
 * Bootstrap §6.18 / Phase E: Treiber registrieren ihre JdbcUrlBuilder,
 * DataReader und TableLister einmal beim Programmstart vor dem ersten
 * Command-Dispatch.
 *
 * `internal` für [Main.kt]-Tests, die die Bootstrap-Sequenz ohne
 * `exitProcess` ausführen wollen.
 */
internal fun registerDrivers() {
    DatabaseDriverRegistry.loadAll()
}

/**
 * Baut die Command-Hierarchie `d-migrate → {schema, data}`. Getrennt von
 * [main], damit Tests die gleiche Struktur wie die Production-Entry ohne
 * `exitProcess` instanziieren können.
 */
internal fun buildRootCommand(): DMigrate =
    DMigrate().subcommands(SchemaCommand(), DataCommand(), ExportCommand())

/**
 * Test-freundlicher Einstieg: führt die Bootstrap-Sequenz aus und
 * dispatched via [CliktCommand.parse]. Wirft bei Fehlern `CliktError`
 * (statt sie über `exitProcess` in einen Prozess-Exit umzuwandeln), damit
 * Unit-Tests den Bootstrap-Pfad abdecken können, ohne das Test-JVM zu
 * killen.
 *
 * Production-Einstieg ist [main], der `runCli(args)`-Aufruf über Clikt's
 * [CliktCommand.main] macht (inkl. Error-Formatierung und `exitProcess`).
 */
internal fun runCli(args: Array<String>) {
    registerDrivers()
    buildRootCommand().parse(args)
}

fun main(args: Array<String>) {
    registerDrivers()
    buildRootCommand().main(args)
}
