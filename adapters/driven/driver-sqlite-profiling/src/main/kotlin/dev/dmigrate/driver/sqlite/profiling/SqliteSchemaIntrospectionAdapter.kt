package dev.dmigrate.driver.sqlite.profiling

import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import java.sql.Connection

/**
 * SQLite implementation of [SchemaIntrospectionPort].
 * Uses PRAGMA-based metadata queries for PK, FK, and unique index detection.
 *
 * SQLite PRAGMAs do not support PreparedStatement binding. Identifier
 * arguments are secured via [SqlIdentifiers.quoteStringLiteral] to
 * prevent injection through crafted table/index names.
 */
class SqliteSchemaIntrospectionAdapter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaIntrospectionPort {

    private fun ql(value: String): String = SqlIdentifiers.quoteStringLiteral(value)

    private inline fun <T> withJdbc(pool: ConnectionPool, block: (JdbcOperations) -> T): T =
        pool.borrow().use { conn -> block(jdbcFactory(conn)) }

    override fun listTables(pool: ConnectionPool, schema: String?): List<TableSchema> =
        withJdbc(pool) { jdbc ->
            jdbc.queryList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
            ).map { row -> TableSchema(row["name"] as String) }
        }

    override fun listColumns(pool: ConnectionPool, table: String, schema: String?): List<ColumnSchema> =
        withJdbc(pool) { jdbc ->
            // Primary keys
            val pkColumns = jdbc.queryList("PRAGMA table_info(${ql(table)})")
                .filter { (it["pk"] as Number).toInt() > 0 }
                .mapTo(mutableSetOf()) { it["name"] as String }

            // Foreign keys
            val fkColumns = jdbc.queryList("PRAGMA foreign_key_list(${ql(table)})")
                .mapTo(mutableSetOf()) { it["from"] as String }

            // Unique indexes (single-column only for simplicity)
            val uniqueColumns = jdbc.queryList("PRAGMA index_list(${ql(table)})")
                .filter { (it["unique"] as Number).toInt() == 1 }
                .flatMap { indexRow ->
                    val indexName = indexRow["name"] as String
                    val cols = jdbc.queryList("PRAGMA index_info(${ql(indexName)})")
                        .map { it["name"] as String }
                    if (cols.size == 1) cols else emptyList()
                }
                .toMutableSet()

            // Build column list
            jdbc.queryList("PRAGMA table_info(${ql(table)})").map { row ->
                val name = row["name"] as String
                ColumnSchema(
                    name = name,
                    dbType = row["type"] as? String ?: "",
                    nullable = (row["notnull"] as Number).toInt() == 0,
                    isPrimaryKey = name in pkColumns,
                    isForeignKey = name in fkColumns,
                    isUnique = name in uniqueColumns,
                )
            }
        }
}
