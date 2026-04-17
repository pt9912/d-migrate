package dev.dmigrate.driver

/**
 * Central, dialect-aware identifier quoting and string literal escaping.
 *
 * Every SQL-producing site that builds identifier or literal tokens from
 * runtime names (table, column, schema, constraint, index) MUST use this
 * utility instead of local string interpolation. This eliminates the
 * injection surface documented in `docs/quality.md` and consolidates the
 * previously scattered quoting implementations in the per-dialect driver
 * modules.
 *
 * Quoting rules per dialect:
 * - **PostgreSQL / SQLite**: double-quote delimited, internal `"` escaped
 *   as `""` (SQL standard).
 * - **MySQL**: backtick delimited, internal `` ` `` escaped as ` `` `.
 *
 * String literal escaping (for contexts where `PreparedStatement` binding
 * is not available, e.g. SQLite `PRAGMA` arguments):
 * - single-quote delimited, internal `'` escaped as `''`.
 */
object SqlIdentifiers {

    /**
     * Quotes a single SQL identifier (table name, column name, etc.).
     *
     * The result is always safe for interpolation into a SQL string —
     * embedded quote characters are escaped according to the dialect.
     */
    fun quoteIdentifier(name: String, dialect: DatabaseDialect): String =
        when (dialect) {
            DatabaseDialect.MYSQL -> "`${name.replace("`", "``")}`"
            DatabaseDialect.POSTGRESQL,
            DatabaseDialect.SQLITE,
                -> "\"${name.replace("\"", "\"\"")}\""
        }

    /**
     * Quotes a potentially schema-qualified identifier (`schema.table`).
     *
     * Each segment is quoted individually so that `public.users` becomes
     * `"public"."users"` (PostgreSQL/SQLite) or `` `public`.`users` ``
     * (MySQL).
     */
    fun quoteQualifiedIdentifier(qualifiedName: String, dialect: DatabaseDialect): String =
        qualifiedName.split('.').joinToString(".") { quoteIdentifier(it, dialect) }

    /**
     * Escapes a string value for safe interpolation as a SQL string
     * literal. Use this **only** where `PreparedStatement` binding is not
     * available (e.g. SQLite `PRAGMA` arguments). Prefer parameter binding
     * in all other cases.
     */
    fun quoteStringLiteral(value: String): String =
        "'${value.replace("'", "''")}'"
}
