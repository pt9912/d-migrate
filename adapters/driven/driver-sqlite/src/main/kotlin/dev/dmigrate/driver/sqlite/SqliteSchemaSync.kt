package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import java.sql.Connection

class SqliteSchemaSync : SchemaSync {

    override fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment> = reseedGenerators(
        conn = conn,
        table = table,
        importedColumns = importedColumns,
        truncatePerformed = false,
    )

    internal fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
        truncatePerformed: Boolean,
    ): List<SequenceAdjustment> {
        val qualified = parseSqliteQualifiedTableName(table)
        val autoIncrementColumn = lookupAutoincrementColumn(conn, qualified) ?: return emptyList()
        val importedAutoIncrementColumn = importedColumns.any { it.name == autoIncrementColumn }
        if (!importedAutoIncrementColumn && !truncatePerformed) {
            return emptyList()
        }

        val maxValue = lookupMaxValue(conn, qualified, autoIncrementColumn)
        if (maxValue == null) {
            if (!truncatePerformed) return emptyList()
            clearSqliteSequence(conn, qualified)
            return listOf(
                SequenceAdjustment(
                    table = table,
                    column = autoIncrementColumn,
                    sequenceName = null,
                    newValue = 1,
                )
            )
        }

        setSequenceValue(conn, qualified, maxValue)
        return listOf(
            SequenceAdjustment(
                table = table,
                column = autoIncrementColumn,
                sequenceName = null,
                newValue = maxValue + 1,
            )
        )
    }

    override fun disableTriggers(conn: Connection, table: String) {
        throw UnsupportedTriggerModeException(
            "triggerMode=disable is not supported for SQLite in 0.4.0"
        )
    }

    override fun assertNoUserTriggers(conn: Connection, table: String) {
        throw UnsupportedTriggerModeException(
            "triggerMode=strict is not supported for SQLite in 0.4.0"
        )
    }

    override fun enableTriggers(conn: Connection, table: String) {
        throw UnsupportedTriggerModeException(
            "triggerMode=disable is not supported for SQLite in 0.4.0"
        )
    }

    private fun lookupAutoincrementColumn(
        conn: Connection,
        table: SqliteQualifiedTableName,
    ): String? {
        val tableSql = conn.prepareStatement(
            "SELECT sql FROM ${quoteSqliteIdentifier(table.schemaOrMain())}.sqlite_master WHERE type = 'table' AND name = ?"
        ).use { ps ->
            ps.setString(1, table.table)
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        } ?: return null

        if (!tableSql.contains("AUTOINCREMENT", ignoreCase = true)) {
            return null
        }

        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "PRAGMA ${quoteSqliteIdentifier(table.schemaOrMain())}.table_info(${quoteSqliteStringLiteral(table.table)})"
            ).use { rs ->
                while (rs.next()) {
                    if (rs.getInt("pk") > 0) {
                        return rs.getString("name")
                    }
                }
            }
        }
        return null
    }

    private fun lookupMaxValue(
        conn: Connection,
        table: SqliteQualifiedTableName,
        column: String,
    ): Long? {
        val sql = "SELECT MAX(${quoteSqliteIdentifier(column)}) FROM ${table.quotedPath()}"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                check(rs.next()) { "MAX(...) returned no row for ${table.quotedPath()}" }
                val value = rs.getLong(1)
                return if (rs.wasNull()) null else value
            }
        }
    }

    private fun setSequenceValue(
        conn: Connection,
        table: SqliteQualifiedTableName,
        maxValue: Long,
    ) {
        conn.prepareStatement(
            "INSERT OR REPLACE INTO ${quoteSqliteIdentifier(table.schemaOrMain())}.sqlite_sequence (name, seq) VALUES (?, ?)"
        ).use { ps ->
            ps.setString(1, table.table)
            ps.setLong(2, maxValue)
            ps.executeUpdate()
        }
    }

    private fun clearSqliteSequence(
        conn: Connection,
        table: SqliteQualifiedTableName,
    ) {
        conn.prepareStatement(
            "DELETE FROM ${quoteSqliteIdentifier(table.schemaOrMain())}.sqlite_sequence WHERE name = ?"
        ).use { ps ->
            ps.setString(1, table.table)
            ps.executeUpdate()
        }
    }
}
