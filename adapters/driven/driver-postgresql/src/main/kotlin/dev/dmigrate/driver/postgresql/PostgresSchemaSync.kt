package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection

class PostgresSchemaSync(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : SchemaSync {

    override fun reseedGenerators(
        conn: Connection,
        table: String,
        importedColumns: List<ColumnDescriptor>,
    ): List<SequenceAdjustment> {
        if (importedColumns.isEmpty()) return emptyList()

        val jdbc = jdbcFactory(conn)
        val qualified = parseQualifiedTableName(table)
        val adjustments = mutableListOf<SequenceAdjustment>()

        for (column in importedColumns) {
            val sequenceName = lookupSequenceName(jdbc, qualified, column.name) ?: continue
            val maxValue = lookupMaxValue(jdbc, qualified, column.name) ?: continue
            setSequenceValue(jdbc, sequenceName, maxValue)
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
        val jdbc = jdbcFactory(conn)
        inOwnTransaction(conn) {
            jdbc.execute("ALTER TABLE ${qualified.quotedPath()} DISABLE TRIGGER USER")
        }
    }

    override fun assertNoUserTriggers(conn: Connection, table: String) {
        val qualified = parseQualifiedTableName(table)
        val schema = qualified.schemaOrCurrent(conn)
        val jdbc = jdbcFactory(conn)
        val result = jdbc.querySingle(
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
            """.trimIndent(),
            schema, qualified.table,
        )
        if (result != null) {
            val triggerName = result["tgname"] as? String
            throw IllegalStateException(
                "Table '$table' has user trigger '$triggerName'; " +
                    "triggerMode=strict requires a trigger-free target table"
            )
        }
    }

    override fun enableTriggers(conn: Connection, table: String) {
        val qualified = parseQualifiedTableName(table)
        val jdbc = jdbcFactory(conn)
        inOwnTransaction(conn) {
            jdbc.execute("ALTER TABLE ${qualified.quotedPath()} ENABLE TRIGGER USER")
        }
    }

    private fun lookupSequenceName(
        jdbc: JdbcOperations,
        table: QualifiedTableName,
        column: String,
    ): String? {
        val result = jdbc.querySingle(
            "SELECT pg_get_serial_sequence(?, ?)",
            table.pgCatalogName(), column,
        )
        checkNotNull(result) { "pg_get_serial_sequence returned no row" }
        return result.values.firstOrNull() as? String
    }

    private fun lookupMaxValue(
        jdbc: JdbcOperations,
        table: QualifiedTableName,
        column: String,
    ): Long? {
        val sql = "SELECT MAX(${quotePostgresIdentifier(column)}) AS max_val FROM ${table.quotedPath()}"
        val result = jdbc.querySingle(sql)
        checkNotNull(result) { "MAX(...) returned no row for ${table.quotedPath()}" }
        return (result["max_val"] as? Number)?.toLong()
    }

    private fun setSequenceValue(jdbc: JdbcOperations, sequenceName: String, maxValue: Long) {
        val result = jdbc.querySingle("SELECT setval(?::regclass, ?, true)", sequenceName, maxValue)
        checkNotNull(result) { "setval(...) returned no row for sequence '$sequenceName'" }
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
