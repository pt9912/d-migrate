package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate
import java.nio.file.Files
import java.nio.file.Path

class SchemaValidateCommand : CliktCommand(name = "validate") {
    override fun help(context: Context) = "Validate a schema definition"

    // `-` = stdin (spec/cli-spec.md, 10.3 "Stdin-Unterstützung"). Sonst ein existierender
    // Datei-Pfad; die frühere `.path(mustExist=true, canBeDir=false)`-Prüfung wird für
    // Nicht-`-`-Werte in run() nachgebildet, damit fehlende Datei/Verzeichnis wie bisher als
    // UsageError enden (statt erst im Lesepfad einen anderen Exit-Code zu ergeben).
    val source by option("--source", help = "Path to schema file (YAML/JSON), or '-' for stdin")
        .required()

    override fun run() {
        if (source != "-") {
            val path = Path.of(source)
            if (!Files.exists(path)) throw UsageError("Path \"$source\" does not exist.")
            if (Files.isDirectory(path)) throw UsageError("Path \"$source\" is a directory.")
        }
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
