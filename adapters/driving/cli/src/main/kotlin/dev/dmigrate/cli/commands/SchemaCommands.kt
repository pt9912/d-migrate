package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.format.report.TransformationReportWriter
import dev.dmigrate.format.yaml.YamlSchemaCodec

class SchemaCommand : CliktCommand(name = "schema") {
    override fun help(context: Context) = "Schema management commands"

    init {
        subcommands(SchemaValidateCommand(), SchemaGenerateCommand())
    }

    override fun run() = Unit
}

class SchemaValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate a schema definition"

    val source by option("--source", help = "Path to schema file (YAML)")
        .path(mustExist = true, canBeDir = false)
        .required()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        val formatter = OutputFormatter(ctx)

        val schema = try {
            YamlSchemaCodec().read(source)
        } catch (e: Exception) {
            formatter.printError("Failed to parse schema file: ${e.message}", source.toString())
            throw ProgramResult(7)
        }

        val result = SchemaValidator().validate(schema)
        formatter.printValidationResult(result, schema, source.toString())

        if (!result.isValid) {
            throw ProgramResult(3)
        }
    }
}

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

    val source by option("--source", help = "Path to schema file (YAML)")
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

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        val formatter = OutputFormatter(ctx)
        val request = SchemaGenerateRequest(
            source = source,
            target = target,
            output = output,
            report = report,
            generateRollback = generateRollback,
            outputFormat = ctx.outputFormat,
            verbose = ctx.verbose,
            quiet = ctx.quiet,
        )
        val runner = SchemaGenerateRunner(
            schemaReader = { path -> YamlSchemaCodec().read(path) },
            generatorLookup = SchemaGenerateHelpers::getGenerator,
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
