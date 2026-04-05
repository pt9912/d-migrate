package dev.dmigrate.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.commands.SchemaCommand

data class CliContext(
    val outputFormat: String = "plain",
    val verbose: Boolean = false,
    val quiet: Boolean = false,
    val noColor: Boolean = false
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
        versionOption("0.2.0-SNAPSHOT")
    }

    override fun run() {
        if (verbose && quiet) {
            throw UsageError("--verbose and --quiet are mutually exclusive")
        }
    }

    fun cliContext() = CliContext(outputFormat, verbose, quiet, noColor)
}

fun main(args: Array<String>) = DMigrate()
    .subcommands(SchemaCommand())
    .main(args)
