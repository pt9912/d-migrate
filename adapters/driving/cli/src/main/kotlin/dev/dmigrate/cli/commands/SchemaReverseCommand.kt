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
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.LogScrubber
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.format.SidecarPath
import dev.dmigrate.format.report.ReverseReportWriter

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

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        val formatter = OutputFormatter(ctx)
        val request = SchemaReverseRequest(
            source = source,
            output = output,
            format = format ?: "yaml",
            report = report,
            includeViews = includeViews,
            includeProcedures = includeProcedures,
            includeFunctions = includeFunctions,
            includeTriggers = includeTriggers,
            includeAll = includeAll,
            cliConfigPath = null,
            outputFormat = ctx.outputFormat,
            quiet = ctx.quiet,
            verbose = ctx.verbose,
        )
        val runner = SchemaReverseRunner(
            sourceResolver = { src, cfgPath -> NamedConnectionResolver(configPathFromCli = cfgPath).resolve(src) },
            urlParser = { url -> ConnectionUrlParser.parse(url) },
            poolFactory = { config -> HikariConnectionPoolFactory.create(config) },
            driverLookup = { dialect -> DatabaseDriverRegistry.get(dialect) },
            schemaWriter = { path, schema, fmt -> SchemaFileResolver.writeSchema(path, schema, fmt) },
            reportWriter = { path, input -> ReverseReportWriter().write(path, input) },
            sidecarPath = { path, suffix -> SidecarPath.of(path, suffix) },
            formatValidator = { path, fmt -> SchemaFileResolver.validateOutputPath(path, fmt) },
            urlScrubber = LogScrubber::maskUrl,
            printError = { msg, src -> formatter.printError(msg, src) },
        )
        val exitCode = runner.execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
