package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.cliAuditRecorder
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
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
import dev.dmigrate.profiling.model.DatabaseProfile
import java.nio.file.Path

internal data class DataProfileOptions(
    val source: String,
    val tables: List<String>?,
    val schema: String?,
    val topN: Int,
    val format: String,
    val output: Path?,
    /** Read-only-Öffnung der Quelle (Default an; `--no-read-only` erzwingt read-write). */
    val readOnly: Boolean,
    val cliContext: CliContext,
    val configPath: Path?,
)

internal data class DataProfileWiringBundle(
    val connectionResolver: (String) -> String,
    val dialectResolver: (String) -> DatabaseDialect,
    val poolFactory: (String, DatabaseDialect) -> ConnectionPool,
    val adapterLookup: (DatabaseDialect) -> ProfilingAdapterSet,
    val databaseProduct: (AutoCloseable) -> String = { "unknown" },
    val databaseVersion: (AutoCloseable) -> String? = { null },
    val reportWriter: (DatabaseProfile, String, Path?) -> Unit,
)

internal fun interface DataProfileWiringFactory {
    fun build(configPath: Path?, readOnly: Boolean): DataProfileWiringBundle
}

internal object DefaultDataProfileWiringFactory : DataProfileWiringFactory {

    override fun build(configPath: Path?, readOnly: Boolean): DataProfileWiringBundle {
        val writer = ProfileReportWriter()
        val resolver = NamedConnectionResolver(configPathFromCli = configPath)
        return DataProfileWiringBundle(
            connectionResolver = { src ->
                try {
                    resolver.resolve(src)
                } catch (e: ConfigResolveException) {
                    throw IllegalArgumentException(e.message, e)
                }
            },
            dialectResolver = { url -> ConnectionUrlParser.parse(url).dialect },
            poolFactory = { url, _ ->
                // data profile ist eine reine Lese-Operation → Quelle read-only oeffnen
                // (SQLite: SQLITE_OPEN_READONLY, kein -wal/-shm); --no-read-only schaltet ab.
                val config = EnvCredentialFiller().fill(ConnectionUrlParser.parse(url).copy(readOnly = readOnly))
                HikariConnectionPoolFactory.create(config)
            },
            adapterLookup = ::profilingAdaptersFor,
            reportWriter = { profile, fmt, out -> writer.write(profile, fmt, out) },
        )
    }

    private fun profilingAdaptersFor(dialect: DatabaseDialect) = when (dialect) {
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
}

internal object DataProfileWiring {

    fun execute(
        options: DataProfileOptions,
        factory: DataProfileWiringFactory = DefaultDataProfileWiringFactory,
        recorder: CliAuditRecorder = cliAuditRecorder(options.configPath),
    ): Int = recorder.record("data.profile", listOf(options.source)) {
        executeInner(options, factory)
    }

    private fun executeInner(
        options: DataProfileOptions,
        factory: DataProfileWiringFactory,
    ): Int {
        val bundle = factory.build(options.configPath, options.readOnly)
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
            connectionResolver = bundle.connectionResolver,
            dialectResolver = bundle.dialectResolver,
            poolFactory = bundle.poolFactory,
            adapterLookup = bundle.adapterLookup,
            databaseProduct = bundle.databaseProduct,
            databaseVersion = bundle.databaseVersion,
            reportWriter = bundle.reportWriter,
            stderr = { msg ->
                if (msg.startsWith("[ERROR]") || !options.cliContext.quiet) System.err.println(msg)
            },
        )
        return runner.execute(request)
    }
}
