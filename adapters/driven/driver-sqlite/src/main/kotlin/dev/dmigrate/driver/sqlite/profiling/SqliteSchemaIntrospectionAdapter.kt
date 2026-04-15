package dev.dmigrate.driver.sqlite.profiling

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema

/**
 * SQLite implementation of [SchemaIntrospectionPort].
 * Uses PRAGMA-based metadata queries.
 */
class SqliteSchemaIntrospectionAdapter : SchemaIntrospectionPort {

    override fun listTables(pool: ConnectionPool): List<TableSchema> {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
                )
                val tables = mutableListOf<TableSchema>()
                while (rs.next()) tables += TableSchema(rs.getString("name"))
                return tables
            }
        }
    }

    override fun listColumns(pool: ConnectionPool, table: String): List<ColumnSchema> {
        pool.borrow().use { conn ->
            // Get primary key columns
            val pkColumns = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info('$table')")
                while (rs.next()) {
                    if (rs.getInt("pk") > 0) pkColumns += rs.getString("name")
                }
            }

            // Get columns with metadata
            val columns = mutableListOf<ColumnSchema>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info('$table')")
                while (rs.next()) {
                    columns += ColumnSchema(
                        name = rs.getString("name"),
                        dbType = rs.getString("type"),
                        nullable = rs.getInt("notnull") == 0,
                        isPrimaryKey = rs.getString("name") in pkColumns,
                    )
                }
            }
            return columns
        }
    }
}
