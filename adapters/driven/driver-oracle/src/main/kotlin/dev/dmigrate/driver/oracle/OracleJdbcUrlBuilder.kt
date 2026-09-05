package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder

/**
 * Oracle [JdbcUrlBuilder]. ojdbc "thin"-Treiber, EZConnect-Form
 * (`jdbc:oracle:thin:@//host:port/service_name`); `config.database` ist
 * der Service-Name (nicht die SID), Standard-Listener-Port 1521.
 *
 * Kein Default-Param identifiziert (Slice 1) und TLS über Oracle noch
 * nicht modelliert ([SslSettingsParser] liefert leere [dev.dmigrate.driver.connection.SslSettings]
 * für Oracle) -- der geerbte `buildJdbcUrl()`-Default (Query-String-Anhang)
 * bleibt inert, solange keine Params gesetzt sind.
 */
class OracleJdbcUrlBuilder : JdbcUrlBuilder {

    override val dialect: DatabaseDialect = DatabaseDialect.ORACLE

    override fun defaultParams(): Map<String, String> = emptyMap()

    override fun baseJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == DatabaseDialect.ORACLE) {
            "OracleJdbcUrlBuilder cannot build URL for ${config.dialect}"
        }
        val port = config.port ?: 1521
        return "jdbc:oracle:thin:@//${config.host}:$port/${config.database}"
    }
}
