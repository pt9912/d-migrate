package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder

/**
 * PostgreSQL [JdbcUrlBuilder].
 *
 * Default-Parameter (Plan §3.2 / §6.13):
 * - `ApplicationName=d-migrate` — sichtbar in `pg_stat_activity`,
 *   hilft beim Identifizieren der Verbindung in der DB
 *
 * **Bootstrap**: Wird via [PostgresDriver.register] in der globalen
 * [JdbcUrlBuilderRegistry] registriert. Es gibt KEINE automatische
 * Self-Registration beim Klassenladen — siehe [PostgresDriver]-KDoc.
 */
class PostgresJdbcUrlBuilder : JdbcUrlBuilder {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun defaultParams(): Map<String, String> = mapOf(
        "ApplicationName" to "d-migrate",
    )

    override fun baseJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == DatabaseDialect.POSTGRESQL) {
            "PostgresJdbcUrlBuilder cannot build URL for ${config.dialect}"
        }
        val port = config.port ?: 5432
        return "jdbc:postgresql://${config.host}:$port/${config.database}"
    }

}
