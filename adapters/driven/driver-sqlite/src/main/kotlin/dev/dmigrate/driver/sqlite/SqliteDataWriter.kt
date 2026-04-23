package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.AbstractTableImportSession
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import java.sql.Connection

class SqliteDataWriter : DataWriter {

    override val dialect: DatabaseDialect = DatabaseDialect.SQLITE

    override fun schemaSync() = SqliteSchemaSync()

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession {
        check(options.triggerMode == TriggerMode.FIRE) {
            "triggerMode=${options.triggerMode} is not supported for SQLite — " +
                "the Runner should have validated this via DialectCapabilities"
        }

        val conn = pool.borrow()
        val sync = SqliteSchemaSync()
        val qualified = parseSqliteQualifiedTableName(table)
        var savedAutoCommit: Boolean? = null
        var fkChecksDisabled = false
        try {
            savedAutoCommit = conn.autoCommit
            val targetColumns = loadTargetColumns(conn, qualified)
            val primaryKeyColumns = if (options.onConflict == OnConflict.UPDATE) {
                loadPrimaryKeyColumns(conn, qualified).also {
                    require(it.isNotEmpty()) {
                        "Target table '$table' has no primary key; onConflict=update requires a primary key"
                    }
                }
            } else {
                emptyList()
            }

            if (options.disableFkChecks) {
                if (!conn.autoCommit) {
                    conn.autoCommit = true
                }
                setForeignKeyChecks(conn, enabled = false)
                fkChecksDisabled = true
            }

            // §6.14 non-atomic truncate: DELETE FROM before starting the
            // import transaction so the table stays empty even on failure.
            if (options.truncate) {
                if (!conn.autoCommit) conn.autoCommit = true
                conn.createStatement().use { stmt ->
                    stmt.execute("DELETE FROM ${qualified.quotedPath()}")
                }
            }

            conn.autoCommit = false

            val session = SqliteTableImportSession(
                conn = conn,
                savedAutoCommit = savedAutoCommit,
                table = table,
                qualifiedTable = qualified,
                targetColumns = targetColumns,
                primaryKeyColumns = primaryKeyColumns,
                options = options,
                schemaSync = sync,
                fkChecksDisabled = fkChecksDisabled,
            )
            if (options.truncate) {
                session.markTruncatePerformed()
            }
            return session
        } catch (t: Throwable) {
            try {
                conn.rollback()
            } catch (cleanup: Throwable) {
                t.addSuppressed(cleanup)
            }
            try {
                if (fkChecksDisabled) {
                    conn.autoCommit = true
                    setForeignKeyChecks(conn, enabled = true)
                }
            } catch (cleanup: Throwable) {
                t.addSuppressed(cleanup)
            }
            try {
                if (savedAutoCommit != null) {
                    conn.autoCommit = savedAutoCommit
                }
            } catch (cleanup: Throwable) {
                t.addSuppressed(cleanup)
            }
            try {
                conn.close()
            } catch (cleanup: Throwable) {
                t.addSuppressed(cleanup)
            }
            throw t
        }
    }

    private fun loadTargetColumns(
        conn: Connection,
        table: SqliteQualifiedTableName,
    ): List<TargetColumn> = dev.dmigrate.driver.data.loadTargetColumns(conn, table.quotedPath())

    private fun loadPrimaryKeyColumns(
        conn: Connection,
        table: SqliteQualifiedTableName,
    ): List<String> {
        conn.createStatement().use { stmt ->
            stmt.executeQuery(
                "PRAGMA ${quoteSqliteIdentifier(table.schemaOrMain())}.table_info(${quoteSqliteStringLiteral(table.table)})"
            ).use { rs ->
                val rows = mutableListOf<Pair<Int, String>>()
                while (rs.next()) {
                    val pkOrder = rs.getInt("pk")
                    if (pkOrder > 0) {
                        rows += pkOrder to rs.getString("name")
                    }
                }
                return rows.sortedBy { it.first }.map { it.second }
            }
        }
    }

    internal companion object {
        internal fun setForeignKeyChecks(
            conn: Connection,
            enabled: Boolean,
        ) {
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA foreign_keys = ${if (enabled) "ON" else "OFF"}")
            }
        }
    }
}
