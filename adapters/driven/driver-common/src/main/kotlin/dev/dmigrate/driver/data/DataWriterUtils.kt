package dev.dmigrate.driver.data

import java.sql.Connection
import java.sql.ResultSetMetaData

/**
 * Shared utility for DataWriter implementations.
 *
 * Reads target column metadata from the database via `SELECT * LIMIT 0`
 * and ResultSetMetaData — identical across all dialects.
 */
fun loadTargetColumns(conn: Connection, quotedTablePath: String): List<TargetColumn> {
    conn.prepareStatement("SELECT * FROM $quotedTablePath LIMIT 0").use { ps ->
        ps.executeQuery().use { rs ->
            val md = rs.metaData
            return buildList(md.columnCount) {
                for (i in 1..md.columnCount) {
                    add(
                        TargetColumn(
                            name = md.getColumnLabel(i),
                            nullable = md.isNullable(i) != ResultSetMetaData.columnNoNulls,
                            jdbcType = md.getColumnType(i),
                            sqlTypeName = md.getColumnTypeName(i),
                        )
                    )
                }
            }
        }
    }
}
