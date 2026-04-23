package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder

/**
 * MySQL [JdbcUrlBuilder].
 *
 * Default-Parameter (Plan §3.3 / §6.13):
 * - `useCursorFetch=true` — serverseitiger Cursor für sauberes Streaming.
 *   **Bewusst gegen `Statement.setFetchSize(Integer.MIN_VALUE)`** wegen
 *   row-by-row Protokoll-Overhead und HikariCP-Inkompatibilität.
 *
 * Security-by-default:
 * - `allowPublicKeyRetrieval` wird **nicht** implizit aktiviert.
 *   Falls ein Non-TLS-Setup mit `caching_sha2_password` es benötigt, muss der
 *   Parameter explizit über `ConnectionConfig.params` gesetzt werden.
 */
class MysqlJdbcUrlBuilder : JdbcUrlBuilder {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    // Connector/J 9.x hat `useUnicode` und `characterEncoding` entfernt —
    // der Connector nutzt jetzt automatisch das server-seitige Character-Set.
    override fun defaultParams(): Map<String, String> = mapOf(
        "useCursorFetch" to "true",
        "rewriteBatchedStatements" to "true",
    )

    override fun baseJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == DatabaseDialect.MYSQL) {
            "MysqlJdbcUrlBuilder cannot build URL for ${config.dialect}"
        }
        val port = config.port ?: 3306
        return "jdbc:mysql://${config.host}:$port/${config.database}"
    }

}
