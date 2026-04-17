package dev.dmigrate.driver.postgresql

import java.sql.Connection

internal data class QualifiedTableName(
    val schema: String?,
    val table: String,
) {
    fun quotedPath(): String =
        listOfNotNull(schema, table).joinToString(".") { quotePostgresIdentifier(it) }

    /**
     * Text argument for pg_get_serial_sequence(...). PostgreSQL expects the
     * table name in SQL identifier syntax inside the string value.
     */
    fun pgCatalogName(): String =
        listOfNotNull(schema, table).joinToString(".") { quotePostgresIdentifier(it) }

    fun schemaOrCurrent(conn: Connection): String = schema ?: currentSchema(conn)
}

internal fun parseQualifiedTableName(table: String): QualifiedTableName {
    val parts = table.split('.', limit = 2)
    return if (parts.size == 2) {
        QualifiedTableName(parts[0], parts[1])
    } else {
        QualifiedTableName(schema = null, table = table)
    }
}

internal fun quotePostgresIdentifier(name: String): String =
    dev.dmigrate.driver.SqlIdentifiers.quoteIdentifier(name, dev.dmigrate.driver.DatabaseDialect.POSTGRESQL)

internal fun currentSchema(conn: Connection): String =
    conn.createStatement().use { stmt ->
        stmt.executeQuery("SELECT current_schema()").use { rs ->
            check(rs.next()) { "SELECT current_schema() returned no row" }
            rs.getString(1)
        }
    }
