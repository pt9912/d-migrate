package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.connection.SqlServerJdbcUrl
import dev.dmigrate.driver.connection.SslSettings

/**
 * MSSQL [JdbcUrlBuilder]. mssql-jdbc expects `;key=value` properties
 * (`jdbc:sqlserver://host:port;databaseName=db;key=value`), so
 * [buildJdbcUrl] is overridden wholesale instead of inheriting the
 * `?k=v&` assembly of the interface default.
 */
class MssqlJdbcUrlBuilder : JdbcUrlBuilder {

    override val dialect: DatabaseDialect = DatabaseDialect.MSSQL

    override fun defaultParams(): Map<String, String> = mapOf(
        "applicationName" to "d-migrate",
    )

    override fun baseJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == DatabaseDialect.MSSQL) {
            "MssqlJdbcUrlBuilder cannot build URL for ${config.dialect}"
        }
        return SqlServerJdbcUrl.base(config.host, config.port, config.database)
    }

    override fun buildJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == DatabaseDialect.MSSQL) {
            "MssqlJdbcUrlBuilder cannot build URL for ${config.dialect}"
        }
        return SqlServerJdbcUrl.assemble(config, defaultParams())
    }

    override fun sslParams(ssl: SslSettings): Map<String, String> =
        SqlServerJdbcUrl.sslParams(ssl)
}
