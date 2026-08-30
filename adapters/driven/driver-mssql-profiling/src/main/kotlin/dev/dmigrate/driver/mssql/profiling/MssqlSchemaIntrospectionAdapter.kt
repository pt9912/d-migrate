package dev.dmigrate.driver.mssql.profiling

import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.profiling.port.ColumnSchema
import dev.dmigrate.profiling.port.SchemaIntrospectionPort
import dev.dmigrate.profiling.port.TableSchema
import java.sql.Connection

/**
 * Tabellen- und Spalten-Metadaten fuer das Profiling.
 *
 * Ohne `schema`-Angabe gilt das Standardschema der Verbindung
 * (`SCHEMA_NAME()`), nicht die ganze Datenbank — SQL Server traegt in einer
 * Datenbank beliebig viele Schemata, und ein Profiling ueber alle waere eine
 * andere Frage als die gestellte.
 *
 * Schluessel- und Unique-Eigenschaften kommen aus `sys.*`, nicht aus
 * `INFORMATION_SCHEMA`: nur dort steht, ob ein Unique-**Index** (nicht bloss
 * ein Constraint) auf der Spalte liegt.
 */
class MssqlSchemaIntrospectionAdapter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaIntrospectionPort {

    private inline fun <T> withJdbc(pool: ConnectionPool, block: (JdbcOperations) -> T): T =
        pool.borrow().asJdbc().use { conn -> block(jdbcFactory(conn)) }

    override fun listTables(pool: ConnectionPool, schema: String?): List<TableSchema> =
        withJdbc(pool) { jdbc ->
            jdbc.queryList(
                """
                SELECT s.name AS table_schema, t.name AS table_name
                FROM sys.tables t
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                WHERE s.name = COALESCE(?, SCHEMA_NAME()) AND t.is_ms_shipped = 0
                ORDER BY t.name
                """.trimIndent(),
                schema,
            ).map { row ->
                TableSchema(
                    name = row["table_name"] as String,
                    schema = row["table_schema"]?.toString(),
                )
            }
        }

    override fun listColumns(pool: ConnectionPool, table: String, schema: String?): List<ColumnSchema> =
        withJdbc(pool) { jdbc ->
            val keyColumns = jdbc.queryList(
                """
                SELECT c.name AS column_name, i.is_primary_key, i.is_unique, cnt.index_column_count
                FROM sys.indexes i
                JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                JOIN sys.tables t ON t.object_id = i.object_id
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                CROSS APPLY (
                    SELECT COUNT(*) AS index_column_count FROM sys.index_columns x
                    WHERE x.object_id = i.object_id AND x.index_id = i.index_id AND x.is_included_column = 0
                ) cnt
                WHERE s.name = COALESCE(?, SCHEMA_NAME()) AND t.name = ?
                  AND ic.is_included_column = 0 AND (i.is_primary_key = 1 OR i.is_unique = 1)
                """.trimIndent(),
                schema, table,
            )
            // Nur einspaltige Schluessel gelten als Spalten-Eigenschaft: eine
            // Spalte aus einem zusammengesetzten Unique-Index ist fuer sich
            // genommen nicht eindeutig.
            val single = keyColumns.filter { (it["index_column_count"] as Number).toInt() == 1 }
            val pkColumns = single.filter { it["is_primary_key"] == true }
                .mapTo(mutableSetOf()) { it["column_name"] as String }
            val uniqueColumns = single.filter { it["is_unique"] == true }
                .mapTo(mutableSetOf()) { it["column_name"] as String }

            val fkColumns = jdbc.queryList(
                """
                SELECT c.name AS column_name
                FROM sys.foreign_key_columns fkc
                JOIN sys.columns c ON c.object_id = fkc.parent_object_id
                    AND c.column_id = fkc.parent_column_id
                JOIN sys.tables t ON t.object_id = fkc.parent_object_id
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                WHERE s.name = COALESCE(?, SCHEMA_NAME()) AND t.name = ?
                """.trimIndent(),
                schema, table,
            ).mapTo(mutableSetOf()) { it["column_name"] as String }

            jdbc.queryList(
                """
                SELECT c.name AS column_name, ty.name AS type_name, c.is_nullable,
                       c.max_length, c.precision, c.scale
                FROM sys.columns c
                JOIN sys.types ty ON ty.user_type_id = c.user_type_id
                JOIN sys.tables t ON t.object_id = c.object_id
                JOIN sys.schemas s ON s.schema_id = t.schema_id
                WHERE s.name = COALESCE(?, SCHEMA_NAME()) AND t.name = ?
                ORDER BY c.column_id
                """.trimIndent(),
                schema, table,
            ).map { row ->
                val name = row["column_name"] as String
                ColumnSchema(
                    name = name,
                    dbType = row["type_name"] as String,
                    nullable = row["is_nullable"] == true,
                    isPrimaryKey = name in pkColumns,
                    isForeignKey = name in fkColumns,
                    isUnique = name in uniqueColumns,
                )
            }
        }
}
