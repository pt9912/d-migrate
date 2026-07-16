package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.cliAuditRecorder
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.config.ReverseAutoincrementResolver
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriver
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.SchemaReadReportInput
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
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
    val sqliteAutoincrementWidth: Int? = null,
    val cliContext: CliContext,
    val configPath: Path?,
)

internal data class SchemaReverseWiringBundle(
    val sourceResolver: (String, Path?) -> String,
    val urlParser: (String) -> ConnectionConfig,
    val poolFactory: (ConnectionConfig) -> ConnectionPool,
    val driverLookup: (DatabaseDialect) -> DatabaseDriver,
    val schemaWriter: (Path, SchemaDefinition, String?) -> Unit,
    val reportWriter: (Path, SchemaReadReportInput) -> Unit,
    val sidecarPath: (Path, String) -> Path,
    val formatValidator: (Path, String?) -> Unit,
    val urlScrubber: (String) -> String,
    val printError: (String, String) -> Unit,
)

internal fun interface SchemaReverseWiringFactory {
    fun build(cliContext: CliContext): SchemaReverseWiringBundle
}

internal object DefaultSchemaReverseWiringFactory : SchemaReverseWiringFactory {

    override fun build(cliContext: CliContext): SchemaReverseWiringBundle {
        val formatter = OutputFormatter(cliContext, IcuUnicodeTextService())
        val reportWriter = ReverseReportWriter()
        return SchemaReverseWiringBundle(
            sourceResolver = { src, cfgPath -> NamedConnectionResolver(configPathFromCli = cfgPath).resolve(src) },
            urlParser = EnvCredentialFiller().fillingParser(ConnectionUrlParser::parse),
            poolFactory = { config -> HikariConnectionPoolFactory.create(config) },
            driverLookup = { dialect -> DatabaseDriverRegistry.get(dialect) },
            schemaWriter = { path, schema, fmt -> SchemaFileResolver.writeSchema(path, schema, fmt) },
            reportWriter = { path, input -> reportWriter.write(path, input) },
            sidecarPath = { path, suffix -> SidecarPath.of(path, suffix) },
            formatValidator = { path, fmt -> SchemaFileResolver.validateOutputPath(path, fmt) },
            urlScrubber = LogScrubber::maskUrl,
            printError = { msg, src -> formatter.printError(msg, src) },
        )
    }
}

internal object SchemaReverseWiring {

    fun execute(
        options: SchemaReverseOptions,
        factory: SchemaReverseWiringFactory = DefaultSchemaReverseWiringFactory,
        recorder: CliAuditRecorder = cliAuditRecorder(options.configPath),
    ): Int = recorder.record("schema.reverse", listOf(options.source)) {
        executeInner(options, factory)
    }

    private fun executeInner(
        options: SchemaReverseOptions,
        factory: SchemaReverseWiringFactory,
    ): Int {
        val bundle = factory.build(options.cliContext)
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
            sqliteAutoincrement = ReverseAutoincrementResolver(configPathFromCli = options.configPath)
                .resolve(options.sqliteAutoincrementWidth),
        )
        val runner = SchemaReverseRunner(
            sourceResolver = bundle.sourceResolver,
            urlParser = CredentialFilling.storeOnTop(options.source, bundle.urlParser),
            poolFactory = bundle.poolFactory,
            driverLookup = bundle.driverLookup,
            schemaWriter = bundle.schemaWriter,
            reportWriter = bundle.reportWriter,
            sidecarPath = bundle.sidecarPath,
            formatValidator = bundle.formatValidator,
            urlScrubber = bundle.urlScrubber,
            printError = bundle.printError,
        )
        return runner.execute(request)
    }
}
