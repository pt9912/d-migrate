package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
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
import java.nio.file.Path

internal data class DataProfileOptions(
    val source: String,
    val tables: List<String>?,
    val schema: String?,
    val topN: Int,
    val format: String,
    val output: Path?,
    val cliContext: CliContext,
    val configPath: Path?,
)

internal object DataProfileWiring {

    fun execute(options: DataProfileOptions): Int {
        val writer = ProfileReportWriter()
        val request = DataProfileRequest(
            source = options.source,
            tables = options.tables,
            schema = options.schema,
            topN = options.topN,
            format = options.format,
            output = options.output,
            quiet = options.cliContext.quiet,
        )
        val runner = DataProfileRunner(
            connectionResolver = { src ->
                try {
                    NamedConnectionResolver(configPathFromCli = options.configPath).resolve(src)
                } catch (e: ConfigResolveException) {
                    throw IllegalArgumentException(e.message, e)
                }
            },
            dialectResolver = { url -> ConnectionUrlParser.parse(url).dialect },
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
            stderr = { msg ->
                if (msg.startsWith("[ERROR]") || !options.cliContext.quiet) System.err.println(msg)
            },
        )
        return runner.execute(request)
    }
}
