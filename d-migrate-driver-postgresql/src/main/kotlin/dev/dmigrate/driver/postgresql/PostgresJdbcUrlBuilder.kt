package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.connection.JdbcUrlBuilderRegistry

/**
 * PostgreSQL [JdbcUrlBuilder].
 *
 * Default-Parameter (Plan §3.2 / §6.13):
 * - `ApplicationName=d-migrate` — sichtbar in `pg_stat_activity`,
 *   hilft beim Identifizieren der Verbindung in der DB
 *
 * Self-registers in der [JdbcUrlBuilderRegistry] beim ersten Klassenladen.
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

    companion object {
        /** Registriert eine Instanz in der globalen Registry. Idempotent. */
        fun register() {
            JdbcUrlBuilderRegistry.register(PostgresJdbcUrlBuilder())
        }
    }
}
