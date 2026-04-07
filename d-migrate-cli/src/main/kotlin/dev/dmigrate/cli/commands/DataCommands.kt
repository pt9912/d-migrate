package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate

/**
 * `data` Top-Level-Kommando — bündelt alle Subcommands rund um Datenexport
 * (0.3.0) und Datenimport (folgt in 0.4.0).
 *
 * Plan §3.6 — die Hierarchie ist `d-migrate → data → export`. Beim
 * Zugriff auf das Root-Command `DMigrate` aus `DataExportCommand` müssen
 * deshalb ZWEI parent-hops über den `currentContext` gemacht werden.
 */
class DataCommand : CliktCommand(name = "data") {
    override fun help(context: Context) = "Data export and import commands"

    init {
        subcommands(DataExportCommand())
    }

    override fun run() = Unit
}

/**
 * `d-migrate data export` — streamt Tabellen aus einer Datenbank in eines
 * der drei unterstützten Formate (json/yaml/csv).
 *
 * Plan §3.6 / §6.10 / §6.14 / §6.15 / §6.17. Der Command ist eine dünne
 * Clikt-Schale: er sammelt die CLI-Argumente in einen [DataExportRequest]
 * und delegiert an [DataExportRunner], der die gesamte Geschäftslogik
 * hält. Dieser Split existiert, damit alle Exit-Code-Pfade ohne echten
 * Connection-Pool, ohne Datenbank und ohne Clikt-Kontext testbar sind —
 * siehe `DataExportRunnerTest`.
 */
class DataExportCommand : CliktCommand(name = "export") {
    override fun help(context: Context) =
        "Export tables from a database to JSON, YAML, or CSV"

    val source by option(
        "--source",
        help = "Connection URL or named connection from .d-migrate.yaml",
    ).required()

    val format by option(
        "--format",
        help = "Output format (REQUIRED): json, yaml, csv",
    ).choice("json", "yaml", "csv").required()

    val output by option(
        "--output", "-o",
        help = "Output file (single table) or directory (with --split-files); default: stdout",
    ).path()

    val tables by option(
        "--tables",
        help = "Comma-separated list of tables to export; default: all",
    ).split(",")

    val filter by option(
        "--filter",
        help = "Raw SQL WHERE clause applied to all tables (without the 'WHERE' keyword). " +
            "WARNING: not parameterized — see Plan §6.7 for the trust-boundary contract.",
    )

    val encoding by option(
        "--encoding",
        help = "Output encoding (e.g. utf-8, iso-8859-1); default: utf-8",
    ).default("utf-8")

    val chunkSize by option(
        "--chunk-size",
        help = "Rows per chunk (streaming buffer size); default: 10 000",
    ).int().default(10_000)

    val splitFiles by option(
        "--split-files",
        help = "Write one file per table into the --output directory",
    ).flag()

    val csvDelimiter by option(
        "--csv-delimiter",
        help = "CSV column delimiter; default: ','",
    ).default(",")

    val csvBom by option(
        "--csv-bom",
        help = "Prefix CSV output with a UTF-8 BOM",
    ).flag()

    val csvNoHeader by option(
        "--csv-no-header",
        help = "Omit the CSV header row (§6.17 default: header on)",
    ).flag()

    val nullString by option(
        "--null-string",
        help = "CSV NULL representation; default: empty string",
    ).default("")

    override fun run() {
        // Hierarchie: d-migrate → data → export → ZWEI parent-hops nach oben
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()
        val request = DataExportRequest(
            source = source,
            format = format,
            output = output,
            tables = tables,
            filter = filter,
            encoding = encoding,
            chunkSize = chunkSize,
            splitFiles = splitFiles,
            csvDelimiter = csvDelimiter,
            csvBom = csvBom,
            csvNoHeader = csvNoHeader,
            nullString = nullString,
            cliConfigPath = root?.config,
            quiet = ctx.quiet,
            noProgress = ctx.noProgress,
        )
        val exitCode = DataExportRunner().execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
