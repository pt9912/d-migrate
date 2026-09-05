package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.data.TableLister
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

/**
 * Oracle [TableLister]. Listet die Basistabellen des aktuellen Schemas
 * (= aktueller User) via [OracleMetadataQueries].
 *
 * Borgt sich eine Connection aus dem Pool und gibt sie sofort nach dem
 * Listing zurück (LF-008 / LN-010).
 */
class OracleTableLister(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : TableLister {

    override val dialect: DatabaseDialect = DatabaseDialect.ORACLE

    override fun listTables(pool: ConnectionPool): List<String> {
        pool.borrow().asJdbc().use { conn ->
            val session = jdbcFactory(conn)
            val schema = OracleIdentifiers.currentSchema(session)
            return OracleMetadataQueries.listTableRefs(session, schema).map { it.name }
        }
    }
}
