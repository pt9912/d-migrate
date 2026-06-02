package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate

class SchemaValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate a schema definition"

    val source by option("--source", help = "Path to schema file (YAML/JSON)")
        .path(mustExist = true, canBeDir = false)
        .required()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val exitCode = SchemaValidateWiring.execute(
            SchemaValidateOptions(
                source = source,
                cliContext = root?.cliContext() ?: CliContext(),
            )
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
