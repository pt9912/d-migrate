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

    private inline fun <T> withJdbc(pool: ConnectionPool, block: (JdbcOperations) -> T): T =
        pool.borrow().use { conn -> block(jdbcFactory(conn)) }

    override fun listTables(pool: ConnectionPool, schema: String?): List<TableSchema> =
        withJdbc(pool) { jdbc ->
            jdbc.queryList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
                ORDER BY table_name
            """.trimIndent()).map { row ->
                TableSchema(row["table_name"] as String)
            }
        }

    override fun listColumns(pool: ConnectionPool, table: String, schema: String?): List<ColumnSchema> =
        withJdbc(pool) { jdbc ->
            val fkColumns = jdbc.queryList("""
                SELECT column_name
                FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE() AND table_name = ?
                  AND referenced_table_name IS NOT NULL
            """.trimIndent(), table).map { it["column_name"] as String }.toSet()

            jdbc.queryList("""
                SELECT column_name, column_type, is_nullable, column_key
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = ?
                ORDER BY ordinal_position
            """.trimIndent(), table).map { row ->
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
