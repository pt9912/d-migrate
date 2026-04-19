package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

/**
 * MySQL [TableLister]. Delegates to [MysqlMetadataQueries] for the
 * actual table listing, filtered on the current database.
 *
 * Borrows a connection from the pool and returns it immediately after
 * the listing (Plan §6.18).
 */
class MysqlTableLister(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : TableLister {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    override fun listTables(pool: ConnectionPool): List<String> {
        pool.borrow().use { conn ->
            val database = currentDatabase(conn)
            val session = jdbcFactory(conn)
            return MysqlMetadataQueries.listTableRefs(session, database).map { it.name }
        }
    }
}
