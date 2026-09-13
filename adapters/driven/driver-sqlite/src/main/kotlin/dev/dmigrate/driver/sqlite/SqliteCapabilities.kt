package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.DialectCapabilityProvider
import dev.dmigrate.driver.ServerVersion

/**
 * Was SQLite kann. Begruendung je Feld: die KDoc in [DialectCapabilities].
 */
object SqliteCapabilities : DialectCapabilityProvider {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    override fun capabilities(serverVersion: ServerVersion?): DialectCapabilities = DialectCapabilities(
        supportsComputedExpressionInPlace = true,
        supportsViews = true,
        supportsFunctions = false,
        supportsProcedures = false,
        supportsTriggers = true,
        supportsSequences = false,
        supportsCustomTypes = false,
        supportsPartitioning = false,
        namesSingleColumnConstraints = false,
        supportsDisableFkChecks = true,
        supportsTriggerDisable = false,
        supportsTriggerStrict = false,
        supportsSchemaParameter = false,
        carriesFullTextConfiguration = false,
    )
}
