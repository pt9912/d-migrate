package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * `data` Top-Level-Kommando — bündelt alle Subcommands rund um Datenexport
 * (0.3.0) und Datenimport (0.4.0).
 *
 * Plan §3.6 — die Hierarchie ist `d-migrate → data → {export, import}`.
 * Beim Zugriff auf das Root-Command `DMigrate` aus einem Subcommand müssen
 * deshalb ZWEI parent-hops über den `currentContext` gemacht werden.
 */
class DataCommand : CliktCommand(name = "data") {
    override fun help(context: Context) = "Data export and import commands"

    init {
        subcommands(DataExportCommand(), DataImportCommand())
    }

    override fun run() = Unit
}
