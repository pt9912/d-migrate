package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.driver.metadata.JdbcMetadataSession

/**
 * SQLite [TableLister]. Delegates to [SqliteMetadataQueries] for the
 * actual table listing, excluding internal `sqlite_*` tables.
 *
 * Borrows a connection from the pool and returns it immediately after
 * the listing (see Plan §6.18).
 */
class SqliteTableLister : TableLister {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    override fun listTables(pool: ConnectionPool): List<String> {
        pool.borrow().use { conn ->
            val session = JdbcMetadataSession(conn)
            return SqliteMetadataQueries.listTableRefs(session).map { it.name }
        }
    }
}
