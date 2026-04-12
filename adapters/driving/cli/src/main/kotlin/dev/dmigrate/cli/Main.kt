package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
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
import dev.dmigrate.cli.commands.DataCommand
import dev.dmigrate.cli.commands.SchemaCommand
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.mysql.MysqlDriver
import dev.dmigrate.driver.postgresql.PostgresDriver
import dev.dmigrate.driver.sqlite.SqliteDriver

data class CliContext(
    val outputFormat: String = "plain",
    val verbose: Boolean = false,
    val quiet: Boolean = false,
    val noColor: Boolean = false,
    val noProgress: Boolean = false,
)

class DMigrate : CliktCommand(name = "d-migrate") {
    override fun help(context: Context) = "Database-agnostic migration and data management framework"

    val config by option("--config", "-c", help = "Path to configuration file").path()
    val lang by option("--lang", help = "Language for output (de, en)")
    val outputFormat by option("--output-format", help = "Output format: plain, json, yaml")
        .choice("plain", "json", "yaml").default("plain")
    val verbose by option("--verbose", "-v", help = "Verbose output (DEBUG level)").flag()
    val quiet by option("--quiet", "-q", help = "Only show errors").flag()
    val noColor by option("--no-color", help = "Disable colored output").flag()
    val noProgress by option("--no-progress", help = "Disable progress display").flag()
    val yes by option("--yes", "-y", help = "Accept confirmations automatically").flag()

    init {
        versionOption("0.5.0-SNAPSHOT")
    }

    override fun run() {
        if (verbose && quiet) {
            throw UsageError("--verbose and --quiet are mutually exclusive")
        }
    }

    fun cliContext() = CliContext(outputFormat, verbose, quiet, noColor, noProgress)
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
    DatabaseDriverRegistry.register(PostgresDriver())
    DatabaseDriverRegistry.register(MysqlDriver())
    DatabaseDriverRegistry.register(SqliteDriver())
}

/**
 * Baut die Command-Hierarchie `d-migrate → {schema, data}`. Getrennt von
 * [main], damit Tests die gleiche Struktur wie die Production-Entry ohne
 * `exitProcess` instanziieren können.
 */
internal fun buildRootCommand(): DMigrate =
    DMigrate().subcommands(SchemaCommand(), DataCommand())

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
