package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.TableRef

/**
 * Shared JDBC metadata queries for SQLite.
 *
 * Operates on an already-borrowed connection via [JdbcMetadataSession].
 * Used by both [SqliteTableLister] and the future SchemaReader.
 */
object SqliteMetadataQueries {

    fun listTableRefs(session: JdbcMetadataSession): List<TableRef> {
        val rows = session.queryList(
            "SELECT name FROM sqlite_master " +
                "WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "ORDER BY name"
        )
        return rows.map { row ->
            TableRef(name = row["name"] as String)
        }
    }
}
