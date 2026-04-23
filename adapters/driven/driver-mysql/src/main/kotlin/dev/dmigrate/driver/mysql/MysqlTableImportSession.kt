package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.data.AbstractTableImportSession
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import java.sql.PreparedStatement
import java.sql.Statement

internal class MysqlTableImportSession(
    conn: java.sql.Connection,
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
