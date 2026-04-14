package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.TableRef

/**
 * Shared JDBC metadata queries for MySQL.
 *
 * Operates on an already-borrowed connection via [JdbcMetadataSession].
 * Used by both [MysqlTableLister] and the future SchemaReader.
 */
object MysqlMetadataQueries {

    fun listTableRefs(session: JdbcMetadataSession, database: String): List<TableRef> {
        val rows = session.queryList(
            """
            SELECT table_name, table_schema, table_type
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """.trimIndent(),
            database,
        )
        return rows.map { row ->
            TableRef(
                name = row["table_name"] as String,
                schema = row["table_schema"] as? String,
                type = row["table_type"] as? String ?: "BASE TABLE",
            )
        }
    }
}
