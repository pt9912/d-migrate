package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.connection.SqlServerJdbcUrl
import dev.dmigrate.driver.connection.SslMode
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
        // Same merge precedence as the interface default: defaults < ssl < params.
        val params = LinkedHashMap<String, String>()
        params.putAll(defaultParams())
        params.putAll(sslParams(config.ssl))
        params.putAll(config.params)
        return SqlServerJdbcUrl.append(baseJdbcUrl(config), params)
    }

    /**
     * Neutral [SslMode] onto mssql-jdbc's `encrypt`/`trustServerCertificate`.
     * mssql-jdbc has no opportunistic tier, so ALLOW/PREFER round up to an
     * encrypted-but-unverified connection (like REQUIRE); VERIFY_CA rounds up
     * to full verification because the driver always validates the hostname
     * once it validates the chain. `rootCert` (truststore) is out of scope,
     * mirroring the MySQL builder.
     */
    override fun sslParams(ssl: SslSettings): Map<String, String> = when (ssl.mode) {
        null -> emptyMap()
        SslMode.DISABLE -> mapOf("encrypt" to "false")
        SslMode.ALLOW, SslMode.PREFER, SslMode.REQUIRE ->
            mapOf("encrypt" to "true", "trustServerCertificate" to "true")
        SslMode.VERIFY_CA, SslMode.VERIFY_FULL ->
            mapOf("encrypt" to "true", "trustServerCertificate" to "false")
    }
}
