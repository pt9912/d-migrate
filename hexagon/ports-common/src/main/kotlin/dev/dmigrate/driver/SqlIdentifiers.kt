package dev.dmigrate.driver

/**
 * Central, dialect-aware identifier quoting and string literal escaping.
 *
 * Every SQL-producing site that builds identifier or literal tokens from
 * runtime names (table, column, schema, constraint, index) MUST use this
 * utility instead of local string interpolation. This consolidates the
 * previously scattered quoting implementations in the per-dialect driver
 * modules and keeps the injection surface in one auditable place.
 *
 * Quoting rules per dialect:
 * - **PostgreSQL / SQLite**: double-quote delimited, internal `"` escaped
 *   as `""` (SQL standard).
 * - **MySQL**: backtick delimited, internal `` ` `` escaped as ` `` `.
 *
 * String literal escaping (for contexts where `PreparedStatement` binding
 * is not available, e.g. SQLite `PRAGMA` arguments or generated DDL
 * `DEFAULT` clauses):
 * - single-quote delimited, internal `'` escaped as `''`;
 * - **MySQL additionally doubles backslashes** — unlike the SQL standard it
 *   treats `\` as an escape character in string literals (unless
 *   `NO_BACKSLASH_ESCAPES` is set, which d-migrate does not set), so a value
 *   ending in `\` would otherwise escape the closing quote and break out.
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
     * Escapes a string value for safe interpolation as a single-quoted SQL
     * string literal, escaping per [dialect]. Use this **only** where
     * `PreparedStatement` binding is not available (e.g. SQLite `PRAGMA`
     * arguments, generated DDL `DEFAULT` clauses). Prefer parameter binding
     * in all other cases.
     *
     * The [dialect] is required, not defaulted, so that a MySQL literal can
     * never accidentally be built with SQL-standard escaping — see the
     * backslash note in the class KDoc.
     */
    fun quoteStringLiteral(value: String, dialect: DatabaseDialect): String =
        when (dialect) {
            // Backslash first, then quote: the two target disjoint characters,
            // so order is not correctness-critical, but backslash-first mirrors
            // how MySQL itself resolves escapes.
            DatabaseDialect.MYSQL ->
                "'${value.replace("\\", "\\\\").replace("'", "''")}'"
            DatabaseDialect.POSTGRESQL,
            DatabaseDialect.SQLITE,
                -> "'${value.replace("'", "''")}'"
        }
}
