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
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.cli.output.MessageResolver
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.LogScrubber

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
        val ctx = root?.cliContext() ?: CliContext()
        val parsedFilter = try {
            parseFilter(filter)
        } catch (e: FilterParseException) {
            val err = e.parseError
            val posHint = if (err.index != null) " (at position ${err.index})" else ""
            System.err.println("Error: Invalid --filter expression${posHint}: ${err.message}")
            throw ProgramResult(2)
        }
        val request = DataTransferRequest(
            source = source,
            target = target,
            tables = tables,
            filter = parsedFilter,
            sinceColumn = sinceColumn,
            since = since,
            onConflict = onConflict,
            triggerMode = triggerMode,
            truncate = truncate,
            chunkSize = chunkSize,
            cliConfigPath = root?.config,
            quiet = ctx.quiet,
            noProgress = ctx.noProgress,
        )
        val runner = DataTransferRunner(
            sourceResolver = { src, cfgPath -> NamedConnectionResolver(configPathFromCli = cfgPath).resolve(src) },
            targetResolver = { tgt, cfgPath -> NamedConnectionResolver(configPathFromCli = cfgPath).resolve(tgt) },
            urlParser = { url -> ConnectionUrlParser.parse(url) },
            poolFactory = { config -> HikariConnectionPoolFactory.create(config) },
            driverLookup = { dialect -> DatabaseDriverRegistry.get(dialect) },
            urlScrubber = LogScrubber::maskUrl,
            // data transfer uses plain stderr for errors — no structured
            // json/yaml error envelope via OutputFormatter (see Plan §4.8)
            printError = { msg, src ->
                val msgs = MessageResolver(ctx.locale)
                System.err.println(msgs.text("cli.error.source_format", src, msg))
            },
        )
        val exitCode = runner.execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
