package dev.dmigrate.driver.postgresql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import java.sql.Connection

class PostgresSchemaIntrospectionAdapter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaIntrospectionPort {

    private fun qi(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.POSTGRESQL)

    private inline fun <T> withJdbc(pool: ConnectionPool, block: (JdbcOperations) -> T): T =
        pool.borrow().use { conn -> block(jdbcFactory(conn)) }

    override fun listTables(pool: ConnectionPool, schema: String?): List<TableSchema> =
        withJdbc(pool) { jdbc ->
            jdbc.queryList(
                """
                SELECT table_schema, table_name
                FROM information_schema.tables
                WHERE table_schema = ? AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """.trimIndent(),
                schema ?: "public",
            ).map { row ->
                TableSchema(
                    row["table_name"] as String,
                    row["table_schema"] as String,
                )
            }
        }

    override fun listColumns(pool: ConnectionPool, table: String, schema: String?): List<ColumnSchema> {
        val s = schema ?: "public"
        val regclassValue = "${qi(s)}.${qi(table)}"

        return withJdbc(pool) { jdbc ->
            val pkColumns = jdbc.queryList(
                """
                SELECT a.attname
                FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                WHERE i.indrelid = ?::regclass AND i.indisprimary
                """.trimIndent(),
                regclassValue,
            ).mapTo(mutableSetOf()) { it["attname"] as String }

            val fkColumns = jdbc.queryList(
                """
                SELECT kcu.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
                WHERE tc.table_schema = ? AND tc.table_name = ?
                  AND tc.constraint_type = 'FOREIGN KEY'
                """.trimIndent(),
                s,
                table,
            ).mapTo(mutableSetOf()) { it["column_name"] as String }

            val uniqueColumns = jdbc.queryList(
                """
                SELECT a.attname
                FROM pg_index i
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                WHERE i.indrelid = ?::regclass
                  AND i.indisunique AND NOT i.indisprimary
                  AND array_length(i.indkey, 1) = 1
                """.trimIndent(),
                regclassValue,
            ).mapTo(mutableSetOf()) { it["attname"] as String }

            jdbc.queryList(
                """
                SELECT column_name, data_type, udt_name, is_nullable
                FROM information_schema.columns
                WHERE table_schema = ? AND table_name = ?
                ORDER BY ordinal_position
                """.trimIndent(),
                s,
                table,
            ).map { row ->
                val colName = row["column_name"] as String
                val dataType = row["data_type"] as String
                val udtName = row["udt_name"] as String
                val dbType = if (dataType == "USER-DEFINED") udtName else dataType
                ColumnSchema(
                    name = colName,
                    dbType = dbType,
                    nullable = (row["is_nullable"] as String) == "YES",
                    isPrimaryKey = colName in pkColumns,
                    isForeignKey = colName in fkColumns,
                    isUnique = colName in uniqueColumns,
                )
            }
        }
    }
}
