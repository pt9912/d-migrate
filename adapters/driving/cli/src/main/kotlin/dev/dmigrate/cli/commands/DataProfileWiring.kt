package dev.dmigrate.cli.commands

import dev.dmigrate.cli.CliContext
import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.cliAuditRecorder
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.PoolSettings
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
    /** Aus `database.pool:` aufgelöst (Config > Default); wird in `ConnectionConfig.pool` injiziert. */
    val pool: PoolSettings = PoolSettings(),
)

internal data class DataProfileWiringBundle(
    val connectionResolver: (String) -> String,
    val dialectResolver: (String) -> DatabaseDialect,
    val urlParser: (String) -> ConnectionConfig,
    val credentialFiller: (ConnectionConfig, String) -> ConnectionConfig,
    val poolFactory: (ConnectionConfig) -> ConnectionPool,
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
            // data profile ist eine reine Lese-Operation → Quelle read-only oeffnen
            // (SQLite: SQLITE_OPEN_READONLY, kein -wal/-shm); --no-read-only schaltet ab.
            // LN-049 Stufe 2 (Env D_MIGRATE_DB_PASSWORD) hier im urlParser; Stufe 4 (Store) im credentialFiller.
            urlParser = { url -> EnvCredentialFiller().fill(ConnectionUrlParser.parse(url).copy(readOnly = readOnly)) },
            // LN-049 Stufe 4: Store-Konsum keyed nach --source (prozess-weite Session = ein Master-Prompt).
            credentialFiller = CredentialFilling.perConnectionStoreFiller(),
            poolFactory = { config -> HikariConnectionPoolFactory.create(config) },
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
        DatabaseDialect.MSSQL -> error(
            "unreachable: DialectCommandGate rejects mssql for data profile (ADR 0047)",
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
            urlParser = bundle.urlParser,
            credentialFiller = bundle.credentialFiller,
            // pool:-Wiring — aufgelöste PoolSettings injizieren (SQLite bleibt geklemmt).
            poolFactory = { config -> bundle.poolFactory(config.copy(pool = options.pool)) },
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
