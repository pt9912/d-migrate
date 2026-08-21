package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

/**
 * MSSQL identifier helpers: current database/schema resolution and the
 * bracket-quoted `schema.table` form used for `OBJECT_ID(?)` lookups.
 */
internal object MssqlIdentifiers {

    /** Current database: connection catalog, falling back to `DB_NAME()`. */
    fun currentDatabase(conn: Connection): String =
        conn.catalog ?: conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT DB_NAME()").use { rs ->
                rs.next()
                rs.getString(1)
            }
        }

    /** Default schema of the current login (typically `dbo`). */
    fun currentSchema(session: JdbcOperations): String =
        session.querySingle("SELECT SCHEMA_NAME() AS schema_name")
            ?.get("schema_name") as? String
            ?: "dbo"

    /** `[schema].[table]` for `OBJECT_ID(?)` binds; `]` escaped as `]]`. */
    fun qualified(schema: String, name: String): String =
        "${bracket(schema)}.${bracket(name)}"

    fun bracket(name: String): String =
        SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.MSSQL)
}
