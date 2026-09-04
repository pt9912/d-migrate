package dev.dmigrate.cli.commands

import dev.dmigrate.cli.config.ConfigMissingDefaultException
import dev.dmigrate.cli.config.ConfigResolveException
import dev.dmigrate.cli.config.NamedConnectionResolver
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.data.DataWriter
import java.nio.file.Path

/**
 * `--target`-Auflösung über `database.connections`/`database.default_target`,
 * geteilt von [DataImportWiring] und [DataSeedWiring] (beide wiring-Objekte
 * hatten diesen Block zuvor wortwörtlich dupliziert).
 */
internal fun defaultTargetResolver(): (target: String?, configPath: Path?) -> String = { target, configPath ->
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
}

/** `DataWriter`-Lookup über die Treiber-Registry, geteilt von [DataImportWiring] und [DataSeedWiring]. */
internal fun defaultWriterLookup(): (DatabaseDialect) -> DataWriter = { dialect ->
    DatabaseDriverRegistry.get(dialect).dataWriter()
}
