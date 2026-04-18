package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

/**
 * PostgreSQL [TableLister]. Delegates to [PostgresMetadataQueries] for
 * the actual table listing, filtered on the current schema.
 *
 * Borrows a connection from the pool and returns it immediately after
 * the listing (Plan §6.18).
 */
class PostgresTableLister(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : TableLister {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun listTables(pool: ConnectionPool): List<String> {
        pool.borrow().use { conn ->
            val schema = currentSchema(conn)
            val session = jdbcFactory(conn)
            return PostgresMetadataQueries.listTableRefs(session, schema).map { it.name }
        }
    }
}
