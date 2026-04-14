package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.TableRef

/**
 * Shared JDBC metadata queries for PostgreSQL.
 *
 * Operates on an already-borrowed connection via [JdbcMetadataSession].
 * Used by both [PostgresTableLister] and the future SchemaReader.
 */
object PostgresMetadataQueries {

    fun listTableRefs(session: JdbcMetadataSession, schema: String): List<TableRef> {
        val rows = session.queryList(
            """
            SELECT table_name, table_schema, table_type
            FROM information_schema.tables
            WHERE table_schema = ?
              AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """.trimIndent(),
            schema,
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
