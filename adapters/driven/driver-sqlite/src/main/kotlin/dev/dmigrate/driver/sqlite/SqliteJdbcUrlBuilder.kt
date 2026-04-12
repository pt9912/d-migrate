package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder

/**
 * SQLite [JdbcUrlBuilder].
 *
 * Default-Parameter (`connection-config-spec.md` §1.5 + Plan §3.4):
 * - `journal_mode=wal` — WAL-Modus für bessere Concurrency
 * - `foreign_keys=true` — d-migrate verlässt sich auf referenzielle
 *   Integrität, in SQLite sind FKs sonst standardmäßig deaktiviert
 */
class SqliteJdbcUrlBuilder : JdbcUrlBuilder {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    override fun defaultParams(): Map<String, String> = mapOf(
        "journal_mode" to "wal",
        "foreign_keys" to "true",
    )

    override fun baseJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == DatabaseDialect.SQLITE) {
            "SqliteJdbcUrlBuilder cannot build URL for ${config.dialect}"
        }
        return "jdbc:sqlite:${config.database}"
    }

}
