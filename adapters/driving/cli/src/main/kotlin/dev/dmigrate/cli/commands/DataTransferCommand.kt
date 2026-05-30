package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.int
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate

class DataTransferCommand : CliktCommand(name = "transfer") {
    override fun help(context: Context) = "Transfer data directly between databases"

    val source by option("--source", help = "Source database URL or named connection alias")
        .required()
    val target by option("--target", help = "Target database URL or named connection alias")
        .required()
    val tables by option("--tables", help = "Comma-separated list of tables to transfer")
        .split(",")
    val filter by option("--filter", help = "Filter DSL expression for source filtering. Same grammar as data export --filter.")
    val sinceColumn by option("--since-column", help = "Column for incremental transfer")
    val since by option("--since", help = "Value for incremental transfer (requires --since-column)")
    val onConflict by option("--on-conflict", help = "Conflict handling: abort|skip|update")
        .default("abort")
    val triggerMode by option("--trigger-mode", help = "Trigger handling: fire|disable|strict")
        .default("fire")
    val truncate by option("--truncate", help = "Truncate target tables before transfer")
        .flag()
    val chunkSize by option("--chunk-size", help = "Rows per chunk (default: 10000)")
        .int()
        .default(10_000)

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val exitCode = DataTransferWiring.execute(
            DataTransferOptions(
                source = source,
                target = target,
                tables = tables,
                filter = filter,
                sinceColumn = sinceColumn,
                since = since,
                onConflict = onConflict,
                triggerMode = triggerMode,
                truncate = truncate,
                chunkSize = chunkSize,
                cliContext = root?.cliContext() ?: CliContext(),
                configPath = root?.config,
            )
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
