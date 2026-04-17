package dev.dmigrate.driver.sqlite.profiling

import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema

/**
 * SQLite implementation of [SchemaIntrospectionPort].
 * Uses PRAGMA-based metadata queries for PK, FK, and unique index detection.
 *
 * SQLite PRAGMAs do not support PreparedStatement binding. Identifier
 * arguments are secured via [SqlIdentifiers.quoteStringLiteral] to
 * prevent injection through crafted table/index names.
 */
class SqliteSchemaIntrospectionAdapter : SchemaIntrospectionPort {

    private fun ql(value: String): String = SqlIdentifiers.quoteStringLiteral(value)

    override fun listTables(pool: ConnectionPool, schema: String?): List<TableSchema> {
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

    override fun listColumns(pool: ConnectionPool, table: String, schema: String?): List<ColumnSchema> {
        pool.borrow().use { conn ->
            val pkColumns = mutableSetOf<String>()
            val fkColumns = mutableSetOf<String>()
            val uniqueColumns = mutableSetOf<String>()

            // Primary keys
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info(${ql(table)})")
                while (rs.next()) {
                    if (rs.getInt("pk") > 0) pkColumns += rs.getString("name")
                }
            }

            // Foreign keys
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA foreign_key_list(${ql(table)})")
                while (rs.next()) {
                    fkColumns += rs.getString("from")
                }
            }

            // Unique indexes (single-column only for simplicity)
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA index_list(${ql(table)})")
                while (rs.next()) {
                    if (rs.getInt("unique") == 1) {
                        val indexName = rs.getString("name")
                        conn.createStatement().use { s2 ->
                            val irs = s2.executeQuery("PRAGMA index_info(${ql(indexName)})")
                            val cols = mutableListOf<String>()
                            while (irs.next()) cols += irs.getString("name")
                            if (cols.size == 1) uniqueColumns += cols[0]
                        }
                    }
                }
            }

            // Build column list
            val columns = mutableListOf<ColumnSchema>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("PRAGMA table_info(${ql(table)})")
                while (rs.next()) {
                    val name = rs.getString("name")
                    columns += ColumnSchema(
                        name = name,
                        dbType = rs.getString("type"),
                        nullable = rs.getInt("notnull") == 0,
                        isPrimaryKey = name in pkColumns,
                        isForeignKey = name in fkColumns,
                        isUnique = name in uniqueColumns,
                    )
                }
            }
            return columns
        }
    }
}
