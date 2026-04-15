package dev.dmigrate.driver.postgresql.profiling

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema

class PostgresSchemaIntrospectionAdapter : SchemaIntrospectionPort {

    override fun listTables(pool: ConnectionPool): List<TableSchema> {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT table_schema, table_name
                    FROM information_schema.tables
                    WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                    ORDER BY table_name
                """.trimIndent())
                val tables = mutableListOf<TableSchema>()
                while (rs.next()) tables += TableSchema(
                    rs.getString("table_name"),
                    rs.getString("table_schema"),
                )
                return tables
            }
        }
    }

    override fun listColumns(pool: ConnectionPool, table: String): List<ColumnSchema> {
        pool.borrow().use { conn ->
            val pkColumns = mutableSetOf<String>()
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT a.attname
                    FROM pg_index i
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                    WHERE i.indrelid = '"$table"'::regclass AND i.indisprimary
                """.trimIndent())
                while (rs.next()) pkColumns += rs.getString("attname")
            }

            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT column_name, data_type, udt_name, is_nullable,
                           character_maximum_length, numeric_precision, numeric_scale
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = '$table'
                    ORDER BY ordinal_position
                """.trimIndent())
                val columns = mutableListOf<ColumnSchema>()
                while (rs.next()) {
                    val colName = rs.getString("column_name")
                    val dataType = rs.getString("data_type")
                    val udtName = rs.getString("udt_name")
                    val dbType = if (dataType == "USER-DEFINED") udtName else dataType
                    columns += ColumnSchema(
                        name = colName,
                        dbType = dbType,
                        nullable = rs.getString("is_nullable") == "YES",
                        isPrimaryKey = colName in pkColumns,
                    )
                }
                return columns
            }
        }
    }
}
