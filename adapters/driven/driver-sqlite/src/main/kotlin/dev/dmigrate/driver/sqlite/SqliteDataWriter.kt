package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.asJdbc
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

    // LN-013: Clean-Load-Kompensation für --atomic. SQLite kennt kein CASCADE-
    // TRUNCATE → FK-Checks aus (PRAGMA nur außerhalb einer Tx erlaubt, daher
    // autoCommit=true), DELETE je Tabelle, FK-Checks wieder an.
    override fun truncateTables(pool: ConnectionPool, tables: List<String>) {
        if (tables.isEmpty()) return
        pool.borrow().use { handle -> deleteAllWithFkOff(handle.asJdbc(), tables) }
    }

    private fun deleteAllWithFkOff(conn: Connection, tables: List<String>) {
        val savedAutoCommit = conn.autoCommit
        conn.autoCommit = true
        setForeignKeyChecks(conn, enabled = false)
        try {
            conn.createStatement().use { stmt ->
                tables.forEach { stmt.execute("DELETE FROM ${parseSqliteQualifiedTableName(it).quotedPath()}") }
            }
        } finally {
            setForeignKeyChecks(conn, enabled = true)
            conn.autoCommit = savedAutoCommit
        }
    }

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession {
        check(options.triggerMode == TriggerMode.FIRE) {
            "triggerMode=${options.triggerMode} is not supported for SQLite — " +
                "the Runner should have validated this via DialectCapabilities"
        }

        val conn = pool.borrow().asJdbc()
        val sync = SqliteSchemaSync()
        val qualified = parseSqliteQualifiedTableName(table)
        var savedAutoCommit: Boolean? = null
        var fkChecksDisabled = false
        try {
            savedAutoCommit = conn.autoCommit
            val targetColumns = enrichGeometrySrid(conn, qualified, loadTargetColumns(conn, qualified))
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

    /**
     * Reichert Geometrie-Zielspalten mit ihrer SRID aus SpatiaLites
     * `geometry_columns` an, damit der Import WKB als `GeomFromWKB(?, srid)`
     * bindet statt die SRID auf 0 fallen zu lassen.
     *
     * Die Tabelle gibt es nur mit geladener Extension; ohne sie bleibt die
     * Liste unveraendert und der Schreibpfad wickelt nicht.
     */
    private fun enrichGeometrySrid(
        conn: Connection,
        table: SqliteQualifiedTableName,
        columns: List<TargetColumn>,
    ): List<TargetColumn> {
        val sridByColumn = runCatching {
            conn.prepareStatement(
                "SELECT f_geometry_column, srid FROM geometry_columns WHERE lower(f_table_name) = lower(?)",
            ).use { ps ->
                ps.setString(1, table.table)
                ps.executeQuery().use { rs ->
                    buildMap {
                        while (rs.next()) {
                            val srid = rs.getInt("srid").takeIf { it != 0 } ?: continue
                            put(rs.getString("f_geometry_column"), srid)
                        }
                    }
                }
            }
        }.getOrDefault(emptyMap())
        if (sridByColumn.isEmpty()) return columns
        return columns.map { col ->
            sridByColumn.entries.firstOrNull { it.key.equals(col.name, ignoreCase = true) }
                ?.let { col.copy(srid = it.value) } ?: col
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
