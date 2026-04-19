package dev.dmigrate.driver.metadata

import java.sql.Connection
import java.sql.ResultSet

/**
 * Thin helper for executing metadata queries on an already-borrowed
 * [Connection].
 *
 * **Connection ownership**: This class does NOT borrow or close
 * connections. The caller (typically a [dev.dmigrate.driver.SchemaReader]
 * or [dev.dmigrate.driver.data.TableLister]) is responsible for the
 * borrow/close lifecycle via `pool.borrow().use { ... }`.
 */
class JdbcMetadataSession(private val conn: Connection) : JdbcOperations {

    /**
     * Executes a query and maps each result row to a [Map].
     * Column names are lowercased for consistent access.
     */
    override fun queryList(sql: String, vararg params: Any?): List<Map<String, Any?>> {
        if (params.isEmpty()) {
            return conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs -> resultSetToList(rs) }
            }
        }
        conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { i, value -> stmt.setObject(i + 1, value) }
            stmt.executeQuery().use { rs ->
                return resultSetToList(rs)
            }
        }
    }

    /**
     * Executes a query and returns the first row, or null if empty.
     */
    override fun querySingle(sql: String, vararg params: Any?): Map<String, Any?>? {
        if (params.isEmpty()) {
            return conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs -> resultSetToRow(rs) }
            }
        }
        conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { i, value -> stmt.setObject(i + 1, value) }
            stmt.executeQuery().use { rs -> return resultSetToRow(rs) }
        }
    }

    private fun resultSetToRow(rs: ResultSet): Map<String, Any?>? {
        if (!rs.next()) return null
        val meta = rs.metaData
        val row = LinkedHashMap<String, Any?>()
        for (col in 1..meta.columnCount) {
            row[meta.getColumnLabel(col).lowercase()] = rs.getObject(col)
        }
        return row
    }

    override fun execute(sql: String, vararg params: Any?): Int {
        if (params.isEmpty()) {
            return conn.createStatement().use { it.executeUpdate(sql) }
        }
        conn.prepareStatement(sql).use { stmt ->
            params.forEachIndexed { i, value -> stmt.setObject(i + 1, value) }
            return stmt.executeUpdate()
        }
    }

    override fun executeBatch(sql: String, batchParams: List<Array<Any?>>): IntArray {
        conn.prepareStatement(sql).use { stmt ->
            for (row in batchParams) {
                row.forEachIndexed { i, value -> stmt.setObject(i + 1, value) }
                stmt.addBatch()
            }
            return stmt.executeBatch()
        }
    }

    private fun resultSetToList(rs: ResultSet): List<Map<String, Any?>> {
        val meta = rs.metaData
        val colCount = meta.columnCount
        val colNames = (1..colCount).map { meta.getColumnLabel(it).lowercase() }
        val result = mutableListOf<Map<String, Any?>>()
        while (rs.next()) {
            val row = LinkedHashMap<String, Any?>()
            for (i in 1..colCount) {
                row[colNames[i - 1]] = rs.getObject(i)
            }
            result.add(row)
        }
        return result
    }
}
