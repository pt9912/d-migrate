package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

/**
 * MSSQL [TableLister]. Lists base tables of the connection's default
 * schema (typically `dbo`) via [MssqlMetadataQueries].
 *
 * Borrows a connection from the pool and returns it immediately after
 * the listing (LF-008 / LN-010).
 */
class MssqlTableLister(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : TableLister {

    override val dialect: DatabaseDialect = DatabaseDialect.MSSQL

    override fun listTables(pool: ConnectionPool): List<String> {
        pool.borrow().asJdbc().use { conn ->
            val session = jdbcFactory(conn)
            val schema = MssqlIdentifiers.currentSchema(session)
            return MssqlMetadataQueries.listTableRefs(session, schema).map { it.name }
        }
    }
}
