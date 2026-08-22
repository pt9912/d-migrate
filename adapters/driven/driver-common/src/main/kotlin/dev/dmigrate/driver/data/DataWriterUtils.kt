package dev.dmigrate.driver.data

import java.sql.Connection
import java.sql.ResultSetMetaData

/**
 * Runs [action] and adds any thrown exception as suppressed on this
 * [Throwable]. Used in cleanup paths where multiple resources must be
 * released and individual failures must not mask the original exception.
 */
inline fun Throwable.runSuppressing(action: () -> Unit) {
    try {
        action()
    } catch (cleanup: Throwable) {
        addSuppressed(cleanup)
    }
}

/**
 * Shared utility for DataWriter implementations.
 *
 * Reads target column metadata from the database via `SELECT * LIMIT 0`
 * (bzw. [emptyRowsClause] — T-SQL kennt kein `LIMIT`, dort `WHERE 1 = 0`)
 * and ResultSetMetaData — identical across all dialects.
 */
fun loadTargetColumns(
    conn: Connection,
    quotedTablePath: String,
    emptyRowsClause: String = "LIMIT 0",
): List<TargetColumn> {
    conn.prepareStatement("SELECT * FROM $quotedTablePath $emptyRowsClause").use { ps ->
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
