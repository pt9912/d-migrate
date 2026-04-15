package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema

class MysqlSchemaIntrospectionAdapter : SchemaIntrospectionPort {

    override fun listTables(pool: ConnectionPool): List<TableSchema> {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                    ORDER BY table_name
                """.trimIndent())
                val tables = mutableListOf<TableSchema>()
                while (rs.next()) tables += TableSchema(rs.getString("table_name"))
                return tables
            }
        }
    }

    override fun listColumns(pool: ConnectionPool, table: String): List<ColumnSchema> {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT column_name, column_type, is_nullable, column_key
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = '$table'
                    ORDER BY ordinal_position
                """.trimIndent())
                val columns = mutableListOf<ColumnSchema>()
                while (rs.next()) {
                    columns += ColumnSchema(
                        name = rs.getString("column_name"),
                        dbType = rs.getString("column_type"),
                        nullable = rs.getString("is_nullable") == "YES",
                        isPrimaryKey = rs.getString("column_key") == "PRI",
                        isUnique = rs.getString("column_key") == "UNI",
                    )
                }
                return columns
            }
        }
    }
}
