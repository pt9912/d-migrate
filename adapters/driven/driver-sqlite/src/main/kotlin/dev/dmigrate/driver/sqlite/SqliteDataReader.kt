package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.data.AbstractJdbcDataReader

/**
 * SQLite [dev.dmigrate.driver.data.DataReader].
 *
 * SQLite-Spezifika (siehe Plan §3.4 und §6.13):
 * - Kein echtes Cursor-Streaming nötig — SQLite hält die DB ohnehin im
 *   Prozess; ein einfacher ResultSet-Iterator reicht.
 * - `setAutoCommit(false)` ist nicht zwingend notwendig, schadet aber auch
 *   nicht — wir lassen den AbstractJdbcDataReader-Default greifen.
 * - Quoting: doppelte Anführungszeichen, identisch zu PostgreSQL.
 */
class SqliteDataReader : AbstractJdbcDataReader() {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    override fun quoteIdentifier(name: String): String =
        "\"${name.replace("\"", "\"\"")}\""

    /**
     * SQLite hat keinen serverseitigen Cursor — ResultSet wird ohnehin lazy
     * aus der Datei gelesen. Default-fetchSize ist hier nur ein Hint.
     */
    override val fetchSize: Int = 1_000

    override val needsAutoCommitFalse: Boolean = false
}
