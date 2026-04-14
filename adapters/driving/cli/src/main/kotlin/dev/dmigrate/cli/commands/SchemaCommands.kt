package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

class SchemaCommand : CliktCommand(name = "schema") {
    override fun help(context: Context) = "Schema management commands"

    init {
        subcommands(SchemaValidateCommand(), SchemaGenerateCommand(), SchemaCompareCommand(), SchemaReverseCommand())
    }

    override fun run() = Unit
}
