package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate

/**
 * `d-migrate data profile` — thin Clikt shell over [DataProfileRunner].
 */
class DataProfileCommand : CliktCommand(name = "profile") {
    override fun help(context: Context) =
        "Profile a database: column statistics, quality warnings, and target type compatibility"

    val source by option("--source", help = "Database URL or named connection")
        .required()
    val tables by option("--tables", help = "Comma-separated table names (default: all)")
        .split(",")
    val schema by option("--schema", help = "Database schema (supported for PostgreSQL and MySQL, default: none/public)")
    val topN by option("--top-n", help = "Number of top values per column (default: 10)")
        .int().default(10)
    val format by option("--format", help = "Output format: json, yaml (default: json)")
        .choice("json", "yaml").default("json")
    val output by option("--output", help = "Output file path (default: stdout)")
        .path()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val exitCode = DataProfileWiring.execute(
            DataProfileOptions(
                source = source,
                tables = tables,
                schema = schema,
                topN = topN,
                format = format,
                output = output,
                cliContext = root?.cliContext() ?: CliContext(),
                configPath = root?.config,
            )
        )
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
