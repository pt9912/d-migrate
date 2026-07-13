package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder

/**
 * SQLite [JdbcUrlBuilder].
 *
 * LF-003 / LF-004 / LN-009: Default-Parameter:
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

    /**
     * Read-only-Quellen ([ConnectionConfig.readOnly]) öffnen mit
     * `SQLITE_OPEN_READONLY` über die URI-Form `file:<db>?mode=ro`. Bewusst
     * OHNE `journal_mode=wal` (WAL braucht Schreibrecht) → profilierbar auch bei
     * nicht-schreibbarer Quelle, ohne `-wal`/`-shm`-Nebendateien. `:memory:`
     * ist immer schreibbar/ephemer und ignoriert das Flag (Default-Pfad).
     */
    override fun buildJdbcUrl(config: ConnectionConfig): String {
        if (config.readOnly && config.database != ":memory:") {
            require(config.dialect == DatabaseDialect.SQLITE) {
                "SqliteJdbcUrlBuilder cannot build URL for ${config.dialect}"
            }
            // Collapse leading slashes to one: the `file:` URI form reads a `//…`
            // prefix as an authority (`file://tmp/x` → authority `tmp` → SQLITE_ERROR),
            // whereas the plain `jdbc:sqlite:` form tolerates it. `sqlite:///<abspath>`
            // URLs (path already starts with `/`) parse to a `//`-prefixed database.
            val path = config.database.replaceFirst(Regex("^/+"), "/")
            return "jdbc:sqlite:file:$path?mode=ro"
        }
        return super<JdbcUrlBuilder>.buildJdbcUrl(config)
    }

}
