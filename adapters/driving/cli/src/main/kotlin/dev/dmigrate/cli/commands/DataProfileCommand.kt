package dev.dmigrate.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.split
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.DMigrate
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.mysql.profiling.MysqlLogicalTypeResolver
import dev.dmigrate.driver.mysql.profiling.MysqlProfilingDataAdapter
import dev.dmigrate.driver.mysql.profiling.MysqlSchemaIntrospectionAdapter
import dev.dmigrate.driver.postgresql.profiling.PostgresLogicalTypeResolver
import dev.dmigrate.driver.postgresql.profiling.PostgresProfilingDataAdapter
import dev.dmigrate.driver.postgresql.profiling.PostgresSchemaIntrospectionAdapter
import dev.dmigrate.driver.sqlite.profiling.SqliteLogicalTypeResolver
import dev.dmigrate.driver.sqlite.profiling.SqliteProfilingDataAdapter
import dev.dmigrate.driver.sqlite.profiling.SqliteSchemaIntrospectionAdapter
import dev.dmigrate.format.report.ProfileReportWriter
import dev.dmigrate.profiling.ProfilingAdapterSet

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
    val schema by option("--schema", help = "Database schema (PostgreSQL only, default: public)")
    val topN by option("--top-n", help = "Number of top values per column (default: 10)")
        .int().default(10)
    val format by option("--format", help = "Output format: json, yaml (default: json)")
        .default("json")
    val output by option("--output", help = "Output file path (default: stdout)")
        .path()

    override fun run() {
        val root = currentContext.parent?.parent?.command as? DMigrate
        val ctx = root?.cliContext() ?: CliContext()

        val writer = ProfileReportWriter()

        val request = DataProfileRequest(
            source = source,
            tables = tables,
            schema = schema,
            topN = topN,
            format = format,
            output = output,
            quiet = ctx.quiet,
        )

        val runner = DataProfileRunner(
            connectionResolver = { src -> src },
            dialectResolver = { url ->
                ConnectionUrlParser.parse(url).dialect
            },
            poolFactory = { url, _ ->
                val config = ConnectionUrlParser.parse(url)
                HikariConnectionPoolFactory.create(config)
            },
            adapterLookup = { dialect ->
                when (dialect) {
                    DatabaseDialect.POSTGRESQL -> ProfilingAdapterSet(
                        PostgresSchemaIntrospectionAdapter(),
                        PostgresProfilingDataAdapter(),
                        PostgresLogicalTypeResolver(),
                    )
                    DatabaseDialect.MYSQL -> ProfilingAdapterSet(
                        MysqlSchemaIntrospectionAdapter(),
                        MysqlProfilingDataAdapter(),
                        MysqlLogicalTypeResolver(),
                    )
                    DatabaseDialect.SQLITE -> ProfilingAdapterSet(
                        SqliteSchemaIntrospectionAdapter(),
                        SqliteProfilingDataAdapter(),
                        SqliteLogicalTypeResolver(),
                    )
                }
            },
            reportWriter = { profile, fmt, out -> writer.write(profile, fmt, out) },
            stderr = { if (!ctx.quiet) System.err.println(it) },
        )

        val exitCode = runner.execute(request)
        if (exitCode != 0) throw ProgramResult(exitCode)
    }
}
