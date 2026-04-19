package dev.dmigrate.driver.mysql

import java.sql.Connection

internal data class MysqlQualifiedTableName(
    val schema: String?,
    val table: String,
) {
    fun quotedPath(): String =
        listOfNotNull(schema, table).joinToString(".") { quoteMysqlIdentifier(it) }

    fun schemaOrCurrent(conn: Connection): String = schema ?: currentDatabase(conn)

    fun metadataSchema(conn: Connection, lowerCaseTableNames: Int): String =
        normalizeMysqlMetadataIdentifier(schemaOrCurrent(conn), lowerCaseTableNames)

    fun metadataTable(lowerCaseTableNames: Int): String =
        normalizeMysqlMetadataIdentifier(table, lowerCaseTableNames)
}

internal fun parseMysqlQualifiedTableName(table: String): MysqlQualifiedTableName {
    val parts = table.split('.', limit = 2)
    return if (parts.size == 2) {
        MysqlQualifiedTableName(parts[0], parts[1])
    } else {
        MysqlQualifiedTableName(schema = null, table = table)
    }
}

internal fun quoteMysqlIdentifier(name: String): String =
    dev.dmigrate.driver.SqlIdentifiers.quoteIdentifier(name, dev.dmigrate.driver.DatabaseDialect.MYSQL)

internal fun currentDatabase(conn: Connection): String =
    conn.catalog ?: conn.createStatement().use { stmt ->
        stmt.executeQuery("SELECT DATABASE()").use { rs ->
            check(rs.next()) { "SELECT DATABASE() returned no row" }
            rs.getString(1)
        }
    }

internal fun lowerCaseTableNames(conn: Connection): Int =
    conn.createStatement().use { stmt ->
        stmt.executeQuery("SELECT @@lower_case_table_names").use { rs ->
            check(rs.next()) { "SELECT @@lower_case_table_names returned no row" }
            rs.getInt(1)
        }
    }

internal fun normalizeMysqlMetadataIdentifier(name: String, lowerCaseTableNames: Int): String =
    if (lowerCaseTableNames == 0) name else name.lowercase()
