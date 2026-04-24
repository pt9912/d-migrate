package dev.dmigrate.driver.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers

/**
 * Shared quoting helpers for profiling adapters that assemble aggregate SQL.
 */
class ProfilingSqlNames(
    private val dialect: DatabaseDialect,
) {
    fun identifier(name: String): String =
        SqlIdentifiers.quoteIdentifier(name, dialect)

    fun tablePath(table: String, schema: String? = null): String =
        if (schema.isNullOrEmpty()) {
            identifier(table)
        } else {
            "${identifier(schema)}.${identifier(table)}"
        }
}
