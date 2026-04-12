package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import java.sql.Connection

class PostgresSchemaSync : SchemaSync {

    override fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment> {
        if (importedColumns.isEmpty()) return emptyList()

        val qualified = parseQualifiedTableName(table)
        val adjustments = mutableListOf<SequenceAdjustment>()

        for (column in importedColumns) {
            val sequenceName = lookupSequenceName(conn, qualified, column.name) ?: continue
            val maxValue = lookupMaxValue(conn, qualified, column.name) ?: continue
            setSequenceValue(conn, sequenceName, maxValue)
            adjustments += SequenceAdjustment(
                table = table,
                column = column.name,
                sequenceName = sequenceName,
                newValue = maxValue + 1,
            )
        }

        return adjustments
    }

    override fun disableTriggers(conn: Connection, table: String) {
        val qualified = parseQualifiedTableName(table)
        inOwnTransaction(conn) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE ${qualified.quotedPath()} DISABLE TRIGGER USER")
            }
        }
    }

    override fun assertNoUserTriggers(conn: Connection, table: String) {
        val qualified = parseQualifiedTableName(table)
        val schema = qualified.schemaOrCurrent(conn)
        conn.prepareStatement(
            """
            SELECT tg.tgname
            FROM pg_trigger tg
            JOIN pg_class cls ON cls.oid = tg.tgrelid
            JOIN pg_namespace ns ON ns.oid = cls.relnamespace
            WHERE NOT tg.tgisinternal
              AND ns.nspname = ?
              AND cls.relname = ?
            ORDER BY tg.tgname
            LIMIT 1
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, schema)
            ps.setString(2, qualified.table)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val triggerName = rs.getString(1)
                    throw IllegalStateException(
                        "Table '$table' has user trigger '$triggerName'; " +
                            "triggerMode=strict requires a trigger-free target table"
                    )
                }
            }
        }
    }

    override fun enableTriggers(conn: Connection, table: String) {
        val qualified = parseQualifiedTableName(table)
        inOwnTransaction(conn) {
            conn.createStatement().use { stmt ->
                stmt.execute("ALTER TABLE ${qualified.quotedPath()} ENABLE TRIGGER USER")
            }
        }
    }

    private fun lookupSequenceName(
        conn: Connection,
        table: QualifiedTableName,
        column: String,
    ): String? =
        conn.prepareStatement("SELECT pg_get_serial_sequence(?, ?)").use { ps ->
            ps.setString(1, table.pgCatalogName())
            ps.setString(2, column)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "pg_get_serial_sequence returned no row" }
                rs.getString(1)
            }
        }

    private fun lookupMaxValue(
        conn: Connection,
        table: QualifiedTableName,
        column: String,
    ): Long? {
        val sql = "SELECT MAX(${quotePostgresIdentifier(column)}) FROM ${table.quotedPath()}"
        conn.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                check(rs.next()) { "MAX(...) returned no row for ${table.quotedPath()}" }
                val value = rs.getLong(1)
                return if (rs.wasNull()) null else value
            }
        }
    }

    private fun setSequenceValue(conn: Connection, sequenceName: String, maxValue: Long) {
        conn.prepareStatement("SELECT setval(?::regclass, ?, true)").use { ps ->
            ps.setString(1, sequenceName)
            ps.setLong(2, maxValue)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "setval(...) returned no row for sequence '$sequenceName'" }
            }
        }
    }

    private inline fun inOwnTransaction(conn: Connection, block: () -> Unit) {
        val savedAutoCommit = conn.autoCommit
        if (savedAutoCommit) {
            conn.autoCommit = false
        }
        try {
            block()
            conn.commit()
        } catch (t: Throwable) {
            try {
                conn.rollback()
            } catch (_: Throwable) {
            }
            throw t
        } finally {
            if (savedAutoCommit) {
                conn.autoCommit = true
            }
        }
    }
}
