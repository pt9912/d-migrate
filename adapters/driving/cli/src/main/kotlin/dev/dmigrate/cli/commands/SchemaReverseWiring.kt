package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.LogScrubber
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.format.SidecarPath
import dev.dmigrate.format.report.ReverseReportWriter
import dev.dmigrate.text.icu.IcuUnicodeTextService
import java.nio.file.Path

internal data class SchemaReverseOptions(
    val source: String,
    val output: Path,
    val format: String?,
    val report: Path?,
    val includeViews: Boolean,
    val includeProcedures: Boolean,
    val includeFunctions: Boolean,
    val includeTriggers: Boolean,
    val includeAll: Boolean,
    val schemaName: String?,
    val schemaVersion: String?,
    val cliContext: CliContext,
    val configPath: Path?,
)

internal object SchemaReverseWiring {

    fun execute(options: SchemaReverseOptions): Int {
        val formatter = OutputFormatter(options.cliContext, IcuUnicodeTextService())
        val request = SchemaReverseRequest(
            source = options.source,
            output = options.output,
            format = options.format ?: "yaml",
            report = options.report,
            includeViews = options.includeViews,
            includeProcedures = options.includeProcedures,
            includeFunctions = options.includeFunctions,
            includeTriggers = options.includeTriggers,
            includeAll = options.includeAll,
            cliConfigPath = options.configPath,
            outputFormat = options.cliContext.outputFormat,
            quiet = options.cliContext.quiet,
            verbose = options.cliContext.verbose,
            schemaName = options.schemaName,
            schemaVersion = options.schemaVersion,
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
        return runner.execute(request)
    }
}
