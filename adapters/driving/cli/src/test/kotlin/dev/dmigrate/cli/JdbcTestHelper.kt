package dev.dmigrate.cli

import java.sql.DriverManager
import java.sql.ResultSet

internal object JdbcTestHelper {

    fun queryAll(jdbcUrl: String, table: String, user: String? = null, password: String? = null): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        val conn = if (user != null) DriverManager.getConnection(jdbcUrl, user, password)
        else DriverManager.getConnection(jdbcUrl)
        conn.use {
            val rs = it.prepareStatement("SELECT * FROM $table ORDER BY id").executeQuery()
            rs.use { collectRows(it, rows) }
        }
        return rows
    }

    private fun collectRows(rs: ResultSet, rows: MutableList<Map<String, Any?>>) {
        val meta = rs.metaData
        while (rs.next()) {
            val row = linkedMapOf<String, Any?>()
            for (i in 1..meta.columnCount) {
                row[meta.getColumnName(i)] = rs.getObject(i)
            }
            rows += row
        }
    }
}
