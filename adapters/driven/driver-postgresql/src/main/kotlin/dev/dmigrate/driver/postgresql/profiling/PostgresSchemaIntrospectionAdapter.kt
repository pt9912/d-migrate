package dev.dmigrate.driver.postgresql.profiling

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema

class PostgresSchemaIntrospectionAdapter : SchemaIntrospectionPort {

    override fun listTables(pool: ConnectionPool, schema: String?): List<TableSchema> {
        pool.borrow().use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT table_schema, table_name
                    FROM information_schema.tables
                    WHERE table_schema = '${schema ?: "public"}' AND table_type = 'BASE TABLE'
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

    override fun listColumns(pool: ConnectionPool, table: String, schema: String?): List<ColumnSchema> {
        pool.borrow().use { conn ->
            val pkColumns = mutableSetOf<String>()
            val fkColumns = mutableSetOf<String>()
            val uniqueColumns = mutableSetOf<String>()

            // Primary keys
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT a.attname
                    FROM pg_index i
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                    WHERE i.indrelid = '"$table"'::regclass AND i.indisprimary
                """.trimIndent())
                while (rs.next()) pkColumns += rs.getString("attname")
            }

            // Foreign keys
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT kcu.column_name
                    FROM information_schema.table_constraints tc
                    JOIN information_schema.key_column_usage kcu
                      ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                    WHERE tc.table_schema = '${schema ?: "public"}' AND tc.table_name = '$table'
                      AND tc.constraint_type = 'FOREIGN KEY'
                """.trimIndent())
                while (rs.next()) fkColumns += rs.getString("column_name")
            }

            // Unique constraints (single-column)
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT a.attname
                    FROM pg_index i
                    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                    WHERE i.indrelid = '"$table"'::regclass
                      AND i.indisunique AND NOT i.indisprimary
                      AND array_length(i.indkey, 1) = 1
                """.trimIndent())
                while (rs.next()) uniqueColumns += rs.getString("attname")
            }

            // Columns
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT column_name, data_type, udt_name, is_nullable
                    FROM information_schema.columns
                    WHERE table_schema = '${schema ?: "public"}' AND table_name = '$table'
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
                        isForeignKey = colName in fkColumns,
                        isUnique = colName in uniqueColumns,
                    )
                }
                return columns
            }
        }
    }
}
