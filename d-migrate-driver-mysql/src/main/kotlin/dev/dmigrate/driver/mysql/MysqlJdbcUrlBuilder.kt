package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.connection.JdbcUrlBuilderRegistry

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
        "useUnicode" to "true",
        "characterEncoding" to "utf8mb4",
    )

    override fun baseJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == DatabaseDialect.MYSQL) {
            "MysqlJdbcUrlBuilder cannot build URL for ${config.dialect}"
        }
        val port = config.port ?: 3306
        return "jdbc:mysql://${config.host}:$port/${config.database}"
    }

    companion object {
        fun register() {
            JdbcUrlBuilderRegistry.register(MysqlJdbcUrlBuilder())
        }
    }
}
