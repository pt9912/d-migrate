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
 * - `useUnicode=true`, `characterEncoding=utf8mb4` — Unicode-Defaults.
 */
class MysqlJdbcUrlBuilder : JdbcUrlBuilder {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    override fun defaultParams(): Map<String, String> = mapOf(
        "useCursorFetch" to "true",
        "rewriteBatchedStatements" to "true",
        "useUnicode" to "true",
        // Java-Charset-Name (NICHT "utf8mb4" — das ist ein MySQL-server-side
        // Encoding, kein Java-Charset). Connector/J wirft UnsupportedEncoding-
        // Exception bei "utf8mb4". MySQL mapped "UTF-8" serverseitig auf utf8mb4.
        "characterEncoding" to "UTF-8",
    )

    override fun baseJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == DatabaseDialect.MYSQL) {
            "MysqlJdbcUrlBuilder cannot build URL for ${config.dialect}"
        }
        val port = config.port ?: 3306
        return "jdbc:mysql://${config.host}:$port/${config.database}"
    }

}
