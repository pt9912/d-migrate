package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.cli.output.OutputFormatter
import dev.dmigrate.core.validation.SchemaValidator
import dev.dmigrate.format.yaml.YamlSchemaCodec

class SchemaCommand : CliktCommand(name = "schema") {
    override fun help(context: Context) = "Schema management commands"

    init {
        subcommands(SchemaValidateCommand())
    }

    override fun run() = Unit
}

class SchemaValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate a schema definition"

    val source by option("--source", help = "Path to schema file (YAML/JSON)")
        .path(mustExist = true, canBeDir = false)
        .required()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: dev.dmigrate.cli.CliContext()
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
