package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DialectCapabilities
import dev.dmigrate.driver.DialectCapabilityProvider
import dev.dmigrate.driver.MeasuredServerVersions
import dev.dmigrate.driver.PostgresServerVersion
import dev.dmigrate.driver.ServerVersion

/**
 * Was PostgreSQL kann — die Antwort liegt beim Dialekt, nicht in einer
 * geteilten Tabelle.
 *
 * Warum eine Faehigkeit steht, wie sie steht, begruendet die KDoc des
 * jeweiligen Feldes in [DialectCapabilities]: dort stehen alle fuenf
 * Antworten nebeneinander, und die Begruendungen sind vergleichend. Hier
 * stehen nur die Werte.
 */
object PostgresCapabilities : DialectCapabilityProvider {

    /** `VIRTUAL` gibt es ab dieser Hauptversion; darunter ist es ein Syntaxfehler. */
    const val VIRTUAL_COMPUTED_SINCE_MAJOR: Int = 18

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun capabilities(serverVersion: ServerVersion?): DialectCapabilities {
        val version = serverVersion as? PostgresServerVersion
        return DialectCapabilities(
            supportsComputedExpressionInPlace = version?.supportsSetExpression ?: false,
            supportsVirtualComputedColumns =
                (version?.major ?: MeasuredServerVersions.POSTGRESQL.major) >= VIRTUAL_COMPUTED_SINCE_MAJOR,
            supportsRawTextSandbox = true,
            supportsViews = true,
            supportsFunctions = true,
            supportsProcedures = true,
            supportsTriggers = true,
            supportsSequences = true,
            supportsCustomTypes = true,
            supportsPartitioning = true,
            supportsDisableFkChecks = false,
            supportsTriggerDisable = true,
            supportsTriggerStrict = true,
            supportsSchemaParameter = true,
            partitionChildrenAreTables = true,
            supportsIndexIncludeColumns = true,
            // Der Reverse liest den system-vergebenen Sequenznamen einer
            // IDENTITY-Spalte schema-qualifiziert zurueck; gerendert wird
            // er von keinem Dialekt, ein Soll-Schema kann ihn also nicht
            // tragen.
            namesIdentitySequences = false,
            // `SERIAL` und `GENERATED ... AS IDENTITY` sind in PostgreSQL
            // zwei verschiedene Dinge, nicht zwei Schreibweisen desselben.
            rendersAutoIncrementAsIdentity = false,
        )
    }
}
