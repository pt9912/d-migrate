package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate

class SchemaCompareCommand : CliktCommand(name = "compare") {
    override fun help(context: Context) = "Compare two schema definitions"

    val source by option("--source", help = "Schema operand: file path, file:<path>, or db:<url-or-alias>")
        .required()
    val target by option("--target", help = "Schema operand: file path, file:<path>, or db:<url-or-alias>")
        .required()
    val output by option("--output", help = "Output file path (default: stdout)")
        .path()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val exitCode = SchemaCompareWiring.execute(
            SchemaCompareOptions(
                source = source,
                target = target,
                output = output,
                cliContext = root?.cliContext() ?: CliContext(),
                configPath = root?.config,
            )
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
