package dev.dmigrate.driver.sqlite

internal data class SqliteQualifiedTableName(
    val schema: String?,
    val table: String,
) {
    fun quotedPath(): String =
        listOfNotNull(schema, table).joinToString(".") { quoteSqliteIdentifier(it) }

    fun schemaOrMain(): String = schema ?: "main"
}

internal fun parseSqliteQualifiedTableName(table: String): SqliteQualifiedTableName {
    val parts = table.split('.', limit = 2)
    return if (parts.size == 2) {
        SqliteQualifiedTableName(parts[0], parts[1])
    } else {
        SqliteQualifiedTableName(schema = null, table = table)
    }
}

internal fun quoteSqliteIdentifier(name: String): String =
    "\"${name.replace("\"", "\"\"")}\""

internal fun quoteSqliteStringLiteral(value: String): String =
    "'${value.replace("'", "''")}'"
