package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.TableLister

/**
 * PostgreSQL [TableLister]. Liest Tabellen aus `information_schema.tables`,
 * gefiltert auf das current schema.
 *
 * Borgt sich die Connection aus dem Pool und gibt sie sofort nach dem
 * Listing zurück (Plan §6.18).
 */
class PostgresTableLister : TableLister {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun listTables(pool: ConnectionPool): List<String> {
        pool.borrow().use { conn ->
            conn.prepareStatement(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """.trimIndent()
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    val tables = mutableListOf<String>()
                    while (rs.next()) {
                        tables += rs.getString(1)
                    }
                    return tables
                }
            }
        }
    }
}
