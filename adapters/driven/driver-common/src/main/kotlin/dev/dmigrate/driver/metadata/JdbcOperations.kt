package dev.dmigrate.driver.metadata

/**
 * Thin abstraction over JDBC query and execution operations.
 *
 * Production implementation: [JdbcMetadataSession] (wraps a borrowed
 * [java.sql.Connection]). In unit tests, this interface can be mocked
 * via MockK to verify SQL-producing adapter logic without a live
 * database.
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

    /**
     * Executes a DML/DDL statement (INSERT, UPDATE, DELETE, CREATE, ALTER, etc.).
     * Returns the number of affected rows.
     */
    fun execute(sql: String, vararg params: Any?): Int

    /**
     * Executes a batch of parameterized statements (e.g. batch INSERT).
     * Each entry in [batchParams] is one set of bind values.
     * Returns the array of update counts per batch entry.
     */
    fun executeBatch(sql: String, batchParams: List<Array<Any?>>): IntArray
}
