package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import java.sql.Connection

class MysqlSchemaIntrospectionAdapter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaIntrospectionPort {

    private data class SchemaPredicate(
        val sql: String,
        val params: Array<Any?>,
    )

    private inline fun <T> withJdbc(pool: ConnectionPool, block: (JdbcOperations) -> T): T =
        pool.borrow().use { conn -> block(jdbcFactory(conn)) }

    private fun schemaPredicate(column: String, schema: String?): SchemaPredicate =
        if (schema == null) {
            SchemaPredicate("$column = DATABASE()", emptyArray())
        } else {
            SchemaPredicate("$column = ?", arrayOf(schema))
        }

    override fun listTables(pool: ConnectionPool, schema: String?): List<TableSchema> =
        withJdbc(pool) { jdbc ->
            val schemaFilter = schemaPredicate("table_schema", schema)
            jdbc.queryList("""
                SELECT table_schema, table_name FROM information_schema.tables
                WHERE ${schemaFilter.sql} AND table_type = 'BASE TABLE'
                ORDER BY table_name
            """.trimIndent(), *schemaFilter.params).map { row ->
                TableSchema(
                    name = row["table_name"] as String,
                    schema = row["table_schema"] as? String,
                )
            }
        }

    override fun listColumns(pool: ConnectionPool, table: String, schema: String?): List<ColumnSchema> =
        withJdbc(pool) { jdbc ->
            val schemaFilter = schemaPredicate("table_schema", schema)
            val params = schemaFilter.params + table
            val fkColumns = jdbc.queryList("""
                SELECT column_name
                FROM information_schema.key_column_usage
                WHERE ${schemaFilter.sql} AND table_name = ?
                  AND referenced_table_name IS NOT NULL
            """.trimIndent(), *params).map { it["column_name"] as String }.toSet()

            jdbc.queryList("""
                SELECT column_name, column_type, is_nullable, column_key
                FROM information_schema.columns
                WHERE ${schemaFilter.sql} AND table_name = ?
                ORDER BY ordinal_position
            """.trimIndent(), *params).map { row ->
                val name = row["column_name"] as String
                ColumnSchema(
                    name = name,
                    dbType = row["column_type"] as String,
                    nullable = (row["is_nullable"] as String) == "YES",
                    isPrimaryKey = (row["column_key"] as? String) == "PRI",
                    isForeignKey = name in fkColumns,
                    isUnique = (row["column_key"] as? String) == "UNI",
                )
            }
        }
}
