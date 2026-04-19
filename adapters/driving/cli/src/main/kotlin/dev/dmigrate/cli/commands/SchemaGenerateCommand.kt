package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.format.report.TransformationReportWriter

/**
 * `d-migrate schema generate` — dünne Clikt-Schale über [SchemaGenerateRunner].
 *
 * Der Command sammelt die CLI-Argumente in einen [SchemaGenerateRequest]
 * und delegiert an [SchemaGenerateRunner]. Die gesamte Verzweigungs-,
 * Formatierungs- und I/O-Koordinierung sitzt im Runner, damit alle
 * Exit-Code-Pfade ohne Clikt und ohne echtes Dateisystem unit-testbar sind
 * — siehe `SchemaGenerateRunnerTest`.
 */
class SchemaGenerateCommand : CliktCommand(name = "generate") {
    override fun help(context: Context) = "Generate database-specific DDL from a schema definition"

    val source by option("--source", help = "Path to schema file (YAML/JSON)")
        .path(mustExist = true, canBeDir = false)
        .required()
    val target by option("--target", help = "Target database dialect (postgresql, mysql, sqlite)")
        .required()
    val output by option("--output", help = "Output file path (default: stdout)")
        .path()
    val report by option("--report", help = "Report file path (default: <output>.report.yaml)")
        .path()
    val generateRollback by option("--generate-rollback", help = "Generate rollback DDL")
        .flag()
    val spatialProfile by option("--spatial-profile",
        help = "Spatial type handling profile (postgis, native, spatialite, none)")
    val split by option("--split",
        help = "DDL output split mode: 'single' (default) or 'pre-post' for import-friendly artifacts")
        .choice("single", "pre-post")
        .default("single")

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        val formatter = OutputFormatter(ctx)
        val splitMode = if (split == "pre-post") SplitMode.PRE_POST else SplitMode.SINGLE
        val request = SchemaGenerateRequest(
            source = source,
            target = target,
            spatialProfile = spatialProfile,
            output = output,
            report = report,
            generateRollback = generateRollback,
            outputFormat = ctx.outputFormat,
            verbose = ctx.verbose,
            quiet = ctx.quiet,
            splitMode = splitMode,
        )
        val runner = SchemaGenerateRunner(
            schemaReader = { path -> SchemaFileResolver.codecForPath(path).read(path) },
            generatorLookup = { DatabaseDriverRegistry.get(it).ddlGenerator() },
            reportWriter = { path, result, schema, dialect, src ->
                TransformationReportWriter().write(path, result, schema, dialect, src)
            },
            formatJsonOutput = SchemaGenerateHelpers::formatJsonOutput,
            sidecarPath = SchemaGenerateHelpers::sidecarPath,
            rollbackPath = SchemaGenerateHelpers::rollbackPath,
            printError = { msg, src -> formatter.printError(msg, src) },
            printValidationResult = { result, schema, src ->
                formatter.printValidationResult(result, schema, src)
            },
        )
        val exitCode = runner.execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
