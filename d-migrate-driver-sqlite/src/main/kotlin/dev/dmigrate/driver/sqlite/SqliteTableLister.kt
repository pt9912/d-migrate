package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.TableLister

/**
 * SQLite [TableLister]. Liest die Tabellen aus `sqlite_master`, schließt
 * interne `sqlite_*`-Tabellen aus.
 *
 * Borgt sich die Connection aus dem Pool und gibt sie sofort nach dem
 * Listing zurück (siehe Plan §6.18).
 */
class SqliteTableLister : TableLister {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    override fun listTables(pool: ConnectionPool): List<String> {
        pool.borrow().use { conn ->
            conn.prepareStatement(
                "SELECT name FROM sqlite_master " +
                    "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                    "ORDER BY name"
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
