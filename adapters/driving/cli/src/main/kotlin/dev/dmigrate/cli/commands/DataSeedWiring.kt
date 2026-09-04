package dev.dmigrate.cli.commands

import dev.dmigrate.cli.audit.CliAuditRecorder
import dev.dmigrate.cli.audit.cliAuditRecorder
import dev.dmigrate.cli.config.ConfigMissingDefaultException
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.ConnectionUrlParser
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
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
        val writerLookup: (DatabaseDialect) -> DataWriter = { dialect ->
            DatabaseDriverRegistry.get(dialect).dataWriter()
        }
        return DataSeedWiringBundle(
            schemaCodec = YamlSchemaCodec(),
            targetResolver = { target, configPath ->
                try {
                    NamedConnectionResolver(configPathFromCli = configPath).resolveTarget(target)
                } catch (e: ConfigMissingDefaultException) {
                    throw CliUsageException(
                        "--target is required when database.default_target is not set.",
                        e,
                    )
                } catch (e: ConfigResolveException) {
                    throw IllegalArgumentException(e.message ?: "Failed to resolve --target.", e)
                }
            },
            urlParser = EnvCredentialFiller().fillingParser(ConnectionUrlParser::parse),
            poolFactory = HikariConnectionPoolFactory::create,
            writerLookup = writerLookup,
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
            poolFactory = bundle.poolFactory,
            writerLookup = bundle.writerLookup,
        )
        return runner.execute(request)
    }
}
