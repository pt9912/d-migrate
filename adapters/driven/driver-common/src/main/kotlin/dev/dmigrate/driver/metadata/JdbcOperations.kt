package dev.dmigrate.driver.metadata

/**
 * Thin abstraction over JDBC query execution.
 *
 * Production implementation: [JdbcMetadataSession] (wraps a borrowed
 * [java.sql.Connection]). In unit tests, this interface can be mocked
 * via MockK to verify SQL-producing adapter logic without a live
 * database.
 *
 * **Scope**: read-only queries (`SELECT`, `PRAGMA`). Write operations
 * (batch insert, DDL, transaction management) are intentionally out
 * of scope — those live in the DataWriter/SchemaSync adapters and
 * will get their own abstraction in a later phase.
 */
interface JdbcOperations {

    /**
     * Executes a query and maps each result row to a [Map] with
     * lowercased column names as keys.
     */
    fun queryList(sql: String, vararg params: Any?): List<Map<String, Any?>>

    /**
     * Executes a query and returns the first row (or `null` if empty).
     */
    fun querySingle(sql: String, vararg params: Any?): Map<String, Any?>?
}
