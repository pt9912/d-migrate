package dev.dmigrate.cli.commands

import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.cliAuditRecorder
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.PoolSettings
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.format.SchemaCodec
import dev.dmigrate.format.yaml.YamlSchemaCodec
import java.nio.file.Path

/**
 * Snapshot aller CLI-Flags, die [DataSeedCommand] gesammelt hat. Hält
 * [DataSeedWiring.execute] Clikt-frei und unit-testbar (analog
 * `DataImportOptions`).
 */
internal data class DataSeedOptions(
    val schema: Path,
    val target: String?,
    val count: Int,
    val seed: Long?,
    val locale: String,
    val configPath: Path?,
    /** Aus `database.pool:` aufgelöst (Config > Default); wird in `ConnectionConfig.pool` injiziert. */
    val pool: PoolSettings = PoolSettings(),
    /** Aus `pipeline.chunk_size` aufgelöst (Config > Default). */
    val chunkSize: Int = DataSeedRequest.DEFAULT_CHUNK_SIZE,
)

internal data class DataSeedWiringBundle(
    val schemaCodec: SchemaCodec,
    val targetResolver: (String?, Path?) -> String,
    val urlParser: (String) -> ConnectionConfig,
    val poolFactory: (ConnectionConfig) -> ConnectionPool,
    val writerLookup: (DatabaseDialect) -> DataWriter,
)

internal fun interface DataSeedWiringFactory {
    fun build(): DataSeedWiringBundle
}

internal object DefaultDataSeedWiringFactory : DataSeedWiringFactory {
    override fun build(): DataSeedWiringBundle {
        return DataSeedWiringBundle(
            schemaCodec = YamlSchemaCodec(),
            targetResolver = defaultTargetResolver(),
            urlParser = EnvCredentialFiller().fillingParser(ConnectionUrlParser::parse),
            poolFactory = HikariConnectionPoolFactory::create,
            writerLookup = defaultWriterLookup(),
        )
    }
}

internal object DataSeedWiring {

    fun execute(
        options: DataSeedOptions,
        factory: DataSeedWiringFactory = DefaultDataSeedWiringFactory,
        recorder: CliAuditRecorder = cliAuditRecorder(options.configPath),
    ): Int = recorder.record("data.seed", listOfNotNull(options.target)) {
        executeInner(options, factory)
    }

    private fun executeInner(options: DataSeedOptions, factory: DataSeedWiringFactory): Int {
        val bundle = factory.build()
        val request = DataSeedRequest(
            schema = options.schema,
            target = options.target,
            count = options.count,
            seed = options.seed,
            locale = options.locale,
            cliConfigPath = options.configPath,
            chunkSize = options.chunkSize,
        )
        val runner = DataSeedRunner(
            schemaCodec = bundle.schemaCodec,
            targetResolver = bundle.targetResolver,
            // Store-Key = --target-Name; bei weggelassenem --target der database.default_target-Name (LN-049).
            urlParser = CredentialFilling.storeOnTop(
                NamedConnectionResolver(configPathFromCli = options.configPath)
                    .connectionName(options.target, "default_target"),
                bundle.urlParser,
            ),
            // pool:-Wiring — aus `database.pool:` aufgelöste PoolSettings injizieren (SQLite bleibt geklemmt).
            poolFactory = { config -> bundle.poolFactory(config.copy(pool = options.pool)) },
            writerLookup = bundle.writerLookup,
        )
        return runner.execute(request)
    }
}
