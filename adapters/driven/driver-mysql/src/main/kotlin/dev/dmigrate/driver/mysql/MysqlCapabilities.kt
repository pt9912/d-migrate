package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.DialectCapabilityProvider
import dev.dmigrate.driver.ServerVersion

/**
 * Was MySQL kann. Begruendung je Feld: die KDoc in [DialectCapabilities].
 */
object MysqlCapabilities : DialectCapabilityProvider {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    override fun capabilities(serverVersion: ServerVersion?): DialectCapabilities = DialectCapabilities(
        supportsComputedExpressionInPlace = true,
        supportsViews = true,
        supportsFunctions = true,
        supportsProcedures = true,
        supportsTriggers = true,
        supportsSequences = false,
        supportsCustomTypes = false,
        supportsPartitioning = true,
        supportsDisableFkChecks = true,
        supportsTriggerDisable = false,
        supportsTriggerStrict = false,
        supportsSchemaParameter = true,
        carriesFullTextConfiguration = false,
    )
}
