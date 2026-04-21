package dev.dmigrate.driver.mysql

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
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement

class MysqlDataWriter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : DataWriter {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    override fun schemaSync() = MysqlSchemaSync(jdbcFactory)

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession {
        when (options.triggerMode) {
            TriggerMode.FIRE -> Unit
            TriggerMode.DISABLE ->
                throw UnsupportedTriggerModeException(
                    "triggerMode=disable is not supported for MySQL in 0.4.0"
                )
            TriggerMode.STRICT ->
                throw UnsupportedTriggerModeException(
                    "triggerMode=strict is not supported for MySQL in 0.4.0"
                )
        }

        val conn = pool.borrow()
        val jdbc = jdbcFactory(conn)
        val sync = schemaSync()
        val qualified = parseMysqlQualifiedTableName(table)
        var savedAutoCommit: Boolean? = null
        var fkChecksDisabled = false
        try {
            savedAutoCommit = conn.autoCommit
            val lowerCaseSetting = lowerCaseTableNames(conn)
            val targetColumns = loadTargetColumns(conn, qualified)
            val primaryKeyColumns = if (options.onConflict == OnConflict.UPDATE) {
                loadPrimaryKeyColumns(conn, qualified, lowerCaseSetting).also {
                    require(it.isNotEmpty()) {
                        "Target table '$table' has no primary key; onConflict=update requires a primary key"
                    }
                }
            } else {
                emptyList()
            }

            if (options.disableFkChecks) {
                jdbc.execute("SET FOREIGN_KEY_CHECKS = 0")
                fkChecksDisabled = true
            }

            // §6.14 non-atomic truncate: DELETE FROM before the import
            // transaction so the table stays empty even on failure.
            if (options.truncate) {
                if (!conn.autoCommit) conn.autoCommit = true
                jdbc.execute("DELETE FROM ${qualified.quotedPath()}")
            }

            conn.autoCommit = false

            val session = MysqlTableImportSession(
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
                if (!conn.autoCommit) conn.rollback()
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
                if (fkChecksDisabled) {
                    jdbc.execute("SET FOREIGN_KEY_CHECKS = 1")
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
        table: MysqlQualifiedTableName,
    ): List<TargetColumn> = dev.dmigrate.driver.data.loadTargetColumns(conn, table.quotedPath())

    private fun loadPrimaryKeyColumns(
        conn: Connection,
        table: MysqlQualifiedTableName,
        lowerCaseSetting: Int,
    ): List<String> {
        val rows = mutableListOf<Pair<Short, String>>()
        conn.metaData.getPrimaryKeys(
            conn.catalog,
            table.metadataSchema(conn, lowerCaseSetting),
            table.metadataTable(lowerCaseSetting),
        ).use { rs ->
            while (rs.next()) {
                rows += rs.getShort("KEY_SEQ") to rs.getString("COLUMN_NAME")
            }
        }
        return rows.sortedBy { it.first }.map { it.second }
    }

    internal companion object {
        internal fun setForeignKeyChecks(
            conn: Connection,
            enabled: Boolean,
        ) {
            conn.createStatement().use { stmt ->
                stmt.execute("SET FOREIGN_KEY_CHECKS = ${if (enabled) 1 else 0}")
            }
        }
    }
}

internal class MysqlTableImportSession(
    conn: Connection,
    savedAutoCommit: Boolean,
    table: String,
    private val qualifiedTable: MysqlQualifiedTableName,
    targetColumns: List<TargetColumn>,
    primaryKeyColumns: List<String>,
    options: ImportOptions,
    private val schemaSync: MysqlSchemaSync,
    private var fkChecksDisabled: Boolean,
) : AbstractTableImportSession(conn, savedAutoCommit, table, targetColumns, primaryKeyColumns, options) {

    private var discardConnection: Boolean = false

    // ─── Dialect hooks ──────────────────────────────────────────────────

    override fun buildInsertSql(importedTargetColumns: List<TargetColumn>): String {
        if (importedTargetColumns.isEmpty()) {
            return when (options.onConflict) {
                OnConflict.ABORT -> "INSERT INTO ${qualifiedTable.quotedPath()} () VALUES ()"
                OnConflict.SKIP -> "INSERT IGNORE INTO ${qualifiedTable.quotedPath()} () VALUES ()"
                OnConflict.UPDATE ->
                    error("onConflict=update requires at least one imported column for MySQL")
            }
        }

        val columnList = importedTargetColumns.joinToString(", ") { quoteMysqlIdentifier(it.name) }
        val placeholders = importedTargetColumns.joinToString(", ") { "?" }
        val baseInsert =
            "INTO ${qualifiedTable.quotedPath()} ($columnList) VALUES ($placeholders)"
        return when (options.onConflict) {
            OnConflict.ABORT -> "INSERT $baseInsert"
            OnConflict.SKIP -> "INSERT IGNORE $baseInsert"
            OnConflict.UPDATE -> "INSERT $baseInsert${buildUpsertClause(importedTargetColumns)}"
        }
    }

    override fun executeChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult = when (options.onConflict) {
        OnConflict.UPDATE -> executeUpsertChunk(importedTargetColumns, rows)
        OnConflict.SKIP -> executeSkipChunk(importedTargetColumns, rows)
        OnConflict.ABORT -> executeBatchChunk(importedTargetColumns, rows)
    }

    override fun bindRow(
        stmt: PreparedStatement,
        importedTargetColumns: List<TargetColumn>,
        row: Array<Any?>,
    ) {
        importedTargetColumns.forEachIndexed { index, targetColumn ->
            val value = row[index]
            if (value == null) {
                stmt.setNull(index + 1, targetColumn.jdbcType)
            } else {
                stmt.setObject(index + 1, value)
            }
        }
    }

    override fun reseedSequences(): List<SequenceAdjustment> =
        schemaSync.reseedGenerators(
            conn = conn,
            table = table,
            importedColumns = importedColumns.orEmpty(),
            truncatePerformed = truncatePerformed,
        )

    override fun finishDialectCleanup(): Throwable? =
        if (fkChecksDisabled) {
            runCatching {
                MysqlDataWriter.setForeignKeyChecks(conn, enabled = true)
                fkChecksDisabled = false
            }.exceptionOrNull()
        } else {
            null
        }

    override fun closeFinally() {
        runCatching { conn.autoCommit = savedAutoCommit }.onFailure(::recordCleanupFailure)
        if (fkChecksDisabled) {
            runCatching {
                MysqlDataWriter.setForeignKeyChecks(conn, enabled = true)
                fkChecksDisabled = false
            }.onFailure {
                discardConnection = true
                recordCleanupFailure(it)
            }
        }
        if (discardConnection) {
            runCatching { conn.abort(DIRECT_EXECUTOR) }.onFailure(::recordCleanupFailure)
        }
    }

    // ─── MySQL-specific execution strategies ────────────────────────────

    private fun buildUpsertClause(importedTargetColumns: List<TargetColumn>): String {
        if (primaryKeyColumns.isEmpty()) {
            error("ON DUPLICATE KEY UPDATE requires primaryKeyColumns to be loaded")
        }
        val pkSet = primaryKeyColumns.toSet()
        val updateColumns = importedTargetColumns.filterNot { it.name in pkSet }
        if (updateColumns.isEmpty()) {
            val pk = quoteMysqlIdentifier(primaryKeyColumns.first())
            return " ON DUPLICATE KEY UPDATE $pk = $pk"
        }
        val assignments = updateColumns.joinToString(", ") {
            val column = quoteMysqlIdentifier(it.name)
            "$column = VALUES($column)"
        }
        return " ON DUPLICATE KEY UPDATE $assignments"
    }

    private fun executeBatchChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        val stmt = preparedStatement!!
        for (row in rows) {
            bindRow(stmt, importedTargetColumns, row)
            stmt.addBatch()
        }
        val counts = stmt.executeBatch()
        return when (options.onConflict) {
            OnConflict.ABORT -> abortWriteResult(counts)
            OnConflict.SKIP -> error("SKIP path is handled separately")
            OnConflict.UPDATE -> error("UPDATE path is handled separately")
        }
    }

    private fun executeSkipChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        val stmt = preparedStatement!!
        var inserted = 0L
        var skipped = 0L
        for (row in rows) {
            bindRow(stmt, importedTargetColumns, row)
            if (stmt.executeUpdate() == 0) skipped++ else inserted++
        }
        return WriteResult(
            rowsInserted = inserted,
            rowsUpdated = 0,
            rowsSkipped = skipped,
        )
    }

    private fun executeUpsertChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        val stmt = preparedStatement!!
        var inserted = 0L
        var updated = 0L
        var unknown = 0L
        for (row in rows) {
            bindRow(stmt, importedTargetColumns, row)
            when (stmt.executeUpdate()) {
                Statement.SUCCESS_NO_INFO -> unknown++
                1 -> inserted++
                0, 2 -> updated++
                else -> updated++
            }
        }
        return WriteResult(
            rowsInserted = inserted,
            rowsUpdated = updated,
            rowsSkipped = 0,
            rowsUnknown = unknown,
        )
    }

    private fun abortWriteResult(counts: IntArray): WriteResult {
        var inserted = 0L
        for (count in counts) {
            inserted += when (count) {
                Statement.SUCCESS_NO_INFO -> 1L
                in 1..Int.MAX_VALUE -> 1L
                else -> 0L
            }
        }
        return WriteResult(
            rowsInserted = inserted,
            rowsUpdated = 0,
            rowsSkipped = 0,
        )
    }
}
