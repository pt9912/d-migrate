package dev.dmigrate.driver.mysql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

class MysqlSchemaSync(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaSync {

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
        val jdbc = jdbcFactory(conn)
        val qualified = parseMysqlQualifiedTableName(table)
        val lowerCaseSetting = lowerCaseTableNames(conn)
        val autoIncrementColumn = lookupAutoIncrementColumn(jdbc, qualified, conn, lowerCaseSetting) ?: return emptyList()
        val importedAutoIncrementColumn = importedColumns.any { it.name == autoIncrementColumn }
        if (!importedAutoIncrementColumn && !truncatePerformed) {
            return emptyList()
        }

        val maxValue = lookupMaxValue(jdbc, qualified, autoIncrementColumn)
        if (maxValue == null) {
            if (!truncatePerformed) return emptyList()
            setAutoIncrement(jdbc, qualified, 1L)
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
        setAutoIncrement(jdbc, qualified, nextValue)
        return listOf(
            SequenceAdjustment(
                table = table,
                column = autoIncrementColumn,
                sequenceName = null,
                newValue = nextValue,
            )
        )
    }

    private fun lookupAutoIncrementColumn(
        jdbc: JdbcOperations,
        table: MysqlQualifiedTableName,
        conn: Connection,
        lowerCaseTableNames: Int,
    ): String? {
        val result = jdbc.querySingle(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = ?
              AND table_name = ?
              AND LOWER(extra) LIKE '%auto_increment%'
            ORDER BY ordinal_position
            LIMIT 1
            """.trimIndent(),
            table.metadataSchema(conn, lowerCaseTableNames),
            table.metadataTable(lowerCaseTableNames),
        )
        return result?.get("column_name") as? String
    }

    private fun lookupMaxValue(
        jdbc: JdbcOperations,
        table: MysqlQualifiedTableName,
        column: String,
    ): Long? {
        val sql = "SELECT MAX(${quoteMysqlIdentifier(column)}) AS max_val FROM ${table.quotedPath()}"
        val result = jdbc.querySingle(sql)
        checkNotNull(result) { "MAX(...) returned no row for ${table.quotedPath()}" }
        return (result["max_val"] as? Number)?.toLong()
    }

    private fun setAutoIncrement(
        jdbc: JdbcOperations,
        table: MysqlQualifiedTableName,
        nextValue: Long,
    ) {
        jdbc.execute("ALTER TABLE ${table.quotedPath()} AUTO_INCREMENT = $nextValue")
    }
}
