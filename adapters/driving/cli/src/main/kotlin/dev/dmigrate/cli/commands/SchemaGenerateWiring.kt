package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.mysqlContext
import dev.dmigrate.driver.sqliteContext
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.format.report.TransformationReportWriter
import dev.dmigrate.text.icu.IcuUnicodeTextService
import java.nio.file.Path

internal data class SchemaGenerateOptions(
    val source: Path,
    val target: String,
    val output: Path?,
    val report: Path?,
    val generateRollback: Boolean,
    val deterministic: Boolean,
    val spatialProfile: String?,
    val split: String,
    val mysqlNamedSequences: String?,
    val sqliteNamedSequences: String?,
    val cliContext: CliContext,
)

internal object SchemaGenerateWiring {

    fun execute(options: SchemaGenerateOptions): Int {
        val formatter = OutputFormatter(options.cliContext, IcuUnicodeTextService())
        val splitMode = if (options.split == "pre-post") SplitMode.PRE_POST else SplitMode.SINGLE
        val request = SchemaGenerateRequest(
            source = options.source,
            target = options.target,
            spatialProfile = options.spatialProfile,
            output = options.output,
            report = options.report,
            generateRollback = options.generateRollback,
            outputFormat = options.cliContext.outputFormat,
            verbose = options.cliContext.verbose,
            quiet = options.cliContext.quiet,
            splitMode = splitMode,
            mysqlNamedSequences = options.mysqlNamedSequences,
            sqliteNamedSequences = options.sqliteNamedSequences,
            deterministic = options.deterministic,
        )
        val runner = SchemaGenerateRunner(
            schemaReader = { path -> SchemaFileResolver.codecForPath(path).read(path) },
            generatorLookup = { DatabaseDriverRegistry.get(it).ddlGenerator() },
            preGenerationValidatorLookup = { DatabaseDriverRegistry.get(it).preGenerationValidator() },
            reportWriter = { path, result, schema, dialect, src, splitModeStr, ddlOptions ->
                TransformationReportWriter().write(
                    path,
                    result,
                    schema,
                    dialect,
                    src,
                    splitModeStr,
                    ddlOptions.mysqlContext?.namedSequenceMode,
                    ddlOptions.generatedAt,
                    ddlOptions.deterministic,
                    ddlOptions.sqliteContext?.namedSequenceMode,
                )
            },
            formatJsonOutput = SchemaGenerateHelpers::formatJsonOutput,
            sidecarPath = SchemaGenerateHelpers::sidecarPath,
            rollbackPath = SchemaGenerateHelpers::rollbackPath,
            splitPath = SchemaGenerateHelpers::splitPath,
            printError = { msg, src -> formatter.printError(msg, src) },
            printValidationResult = { result, schema, src ->
                formatter.printValidationResult(result, schema, src)
            },
        )
        return runner.execute(request)
    }
}
