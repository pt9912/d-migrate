package dev.dmigrate.driver.mysql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import java.sql.Connection

class MysqlSchemaSync : SchemaSync {

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

    fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
        truncatePerformed: Boolean,
    ): List<SequenceAdjustment> {
        val qualified = parseMysqlQualifiedTableName(table)
        val lowerCaseSetting = lowerCaseTableNames(conn)
        val autoIncrementColumn = lookupAutoIncrementColumn(conn, qualified, lowerCaseSetting) ?: return emptyList()
        val importedAutoIncrementColumn = importedColumns.any { it.name == autoIncrementColumn }
        if (!importedAutoIncrementColumn && !truncatePerformed) {
            return emptyList()
        }

        val maxValue = lookupMaxValue(conn, qualified, autoIncrementColumn)
        if (maxValue == null) {
            if (!truncatePerformed) return emptyList()
            setAutoIncrement(conn, qualified, 1L)
            return listOf(
                SequenceAdjustment(
                    table = table,
                    column = autoIncrementColumn,
                    sequenceName = null,
                    newValue = 1,
                )
            )
        }

        val nextValue = maxValue + 1
        setAutoIncrement(conn, qualified, nextValue)
        return listOf(
            SequenceAdjustment(
                table = table,
                column = autoIncrementColumn,
                sequenceName = null,
                newValue = nextValue,
            )
        )
    }

    override fun disableTriggers(conn: Connection, table: String) {
        throw UnsupportedTriggerModeException(
            "triggerMode=disable is not supported for MySQL in 0.4.0"
        )
    }

    override fun assertNoUserTriggers(conn: Connection, table: String) {
        throw UnsupportedTriggerModeException(
            "triggerMode=strict is not supported for MySQL in 0.4.0"
        )
    }

    override fun enableTriggers(conn: Connection, table: String) {
        throw UnsupportedTriggerModeException(
            "triggerMode=disable is not supported for MySQL in 0.4.0"
        )
    }

    private fun lookupAutoIncrementColumn(
        conn: Connection,
        table: MysqlQualifiedTableName,
        lowerCaseTableNames: Int,
    ): String? =
        conn.prepareStatement(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = ?
              AND table_name = ?
              AND LOWER(extra) LIKE '%auto_increment%'
            ORDER BY ordinal_position
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, table.metadataSchema(conn, lowerCaseTableNames))
            ps.setString(2, table.metadataTable(lowerCaseTableNames))
            ps.executeQuery().use { rs ->
                if (rs.next()) rs.getString(1) else null
            }
        }

    private fun lookupMaxValue(
        conn: Connection,
        table: MysqlQualifiedTableName,
        column: String,
    ): Long? {
        val sql = "SELECT MAX(${quoteMysqlIdentifier(column)}) FROM ${table.quotedPath()}"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                check(rs.next()) { "MAX(...) returned no row for ${table.quotedPath()}" }
                val value = rs.getLong(1)
                return if (rs.wasNull()) null else value
            }
        }
    }

    private fun setAutoIncrement(
        conn: Connection,
        table: MysqlQualifiedTableName,
        nextValue: Long,
    ) {
        conn.createStatement().use { stmt ->
            stmt.execute("ALTER TABLE ${table.quotedPath()} AUTO_INCREMENT = $nextValue")
        }
    }
}
