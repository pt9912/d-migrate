package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate

class SchemaReverseCommand : CliktCommand(name = "reverse") {
    override fun help(context: Context) = "Reverse-engineer a live database into a schema file"

    val source by option("--source", help = "Database URL or named connection alias")
        .required()
    val output by option("--output", help = "Output schema file path (YAML/JSON)")
        .path()
        .required()
    val format by option("--format", help = "Output schema format: yaml|json (default: yaml)")
    val report by option("--report", help = "Report file path (default: <output>.report.yaml)")
        .path()
    val includeViews by option("--include-views", help = "Include views").flag()
    val includeProcedures by option("--include-procedures", help = "Include stored procedures").flag()
    val includeFunctions by option("--include-functions", help = "Include user-defined functions").flag()
    val includeTriggers by option("--include-triggers", help = "Include triggers").flag()
    val includeAll by option("--include-all", help = "Include all optional object types").flag()
    val schemaName by option("--name", help = "Schema name to write instead of the reverse-generated default")
    val schemaVersion by option("--version", help = "Schema version to write instead of 0.0.0-reverse")

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val exitCode = SchemaReverseWiring.execute(
            SchemaReverseOptions(
                source = source,
                output = output,
                format = format,
                report = report,
                includeViews = includeViews,
                includeProcedures = includeProcedures,
                includeFunctions = includeFunctions,
                includeTriggers = includeTriggers,
                includeAll = includeAll,
                schemaName = schemaName,
                schemaVersion = schemaVersion,
                cliContext = root?.cliContext() ?: CliContext(),
                configPath = root?.config,
            )
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
