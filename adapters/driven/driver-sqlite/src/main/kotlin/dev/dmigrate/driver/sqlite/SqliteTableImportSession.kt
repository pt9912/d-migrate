package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.data.AbstractTableImportSession
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import java.sql.PreparedStatement
import java.sql.Statement

internal class SqliteTableImportSession(
    conn: java.sql.Connection,
    savedAutoCommit: Boolean,
    table: String,
    private val qualifiedTable: SqliteQualifiedTableName,
    targetColumns: List<TargetColumn>,
    primaryKeyColumns: List<String>,
    options: ImportOptions,
    private val schemaSync: SqliteSchemaSync,
    private var fkChecksDisabled: Boolean,
) : AbstractTableImportSession(conn, savedAutoCommit, table, targetColumns, primaryKeyColumns, options) {

    private var discardConnection: Boolean = false

    override fun buildInsertSql(importedTargetColumns: List<TargetColumn>): String {
        if (importedTargetColumns.isEmpty()) {
            return when (options.onConflict) {
                OnConflict.ABORT -> "INSERT INTO ${qualifiedTable.quotedPath()} DEFAULT VALUES"
                OnConflict.SKIP -> "INSERT OR IGNORE INTO ${qualifiedTable.quotedPath()} DEFAULT VALUES"
                OnConflict.UPDATE ->
                    error("onConflict=update requires at least one imported column for SQLite")
            }
        }

        val columnList = importedTargetColumns.joinToString(", ") { quoteSqliteIdentifier(it.name) }
        val placeholders = importedTargetColumns.joinToString(", ") { "?" }
        val baseInsert = "INSERT INTO ${qualifiedTable.quotedPath()} ($columnList) VALUES ($placeholders)"
        return when (options.onConflict) {
            OnConflict.ABORT -> baseInsert
            OnConflict.SKIP -> "$baseInsert ON CONFLICT DO NOTHING"
            OnConflict.UPDATE -> "$baseInsert${buildUpsertClause(importedTargetColumns)}"
        }
    }

    override fun executeChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult = when (options.onConflict) {
        OnConflict.UPDATE -> executeUpsertChunk(importedTargetColumns, rows)
        else -> executeBatchChunk(importedTargetColumns, rows)
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
                conn.autoCommit = true
                SqliteDataWriter.setForeignKeyChecks(conn, enabled = true)
                fkChecksDisabled = false
            }.exceptionOrNull()
        } else {
            null
        }

    override fun closeFinally() {
        if (fkChecksDisabled) {
            runCatching {
                conn.autoCommit = true
                SqliteDataWriter.setForeignKeyChecks(conn, enabled = true)
                fkChecksDisabled = false
            }.onFailure {
                discardConnection = true
                recordCleanupFailure(it)
            }
        }
        runCatching { conn.autoCommit = savedAutoCommit }.onFailure(::recordCleanupFailure)
        if (discardConnection) {
            runCatching { conn.abort(DIRECT_EXECUTOR) }.onFailure(::recordCleanupFailure)
        }
    }

    private fun buildUpsertClause(importedTargetColumns: List<TargetColumn>): String {
        if (primaryKeyColumns.isEmpty()) {
            error("ON CONFLICT UPDATE requires primaryKeyColumns to be loaded")
        }
        val conflictTarget = primaryKeyColumns.joinToString(", ") { quoteSqliteIdentifier(it) }
        val pkSet = primaryKeyColumns.toSet()
        val updateColumns = importedTargetColumns.filterNot { it.name in pkSet }
        if (updateColumns.isEmpty()) {
            val pk = quoteSqliteIdentifier(primaryKeyColumns.first())
            return " ON CONFLICT ($conflictTarget) DO UPDATE SET $pk = excluded.$pk"
        }
        val assignments = updateColumns.joinToString(", ") {
            val column = quoteSqliteIdentifier(it.name)
            "$column = excluded.$column"
        }
        return " ON CONFLICT ($conflictTarget) DO UPDATE SET $assignments"
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
            OnConflict.ABORT -> batchWriteResult(counts, skipMode = false)
            OnConflict.SKIP -> batchWriteResult(counts, skipMode = true)
            OnConflict.UPDATE -> error("UPDATE path is handled separately")
        }
    }

    private fun executeUpsertChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        val stmt = preparedStatement!!
        val existsSql = buildPkExistsSql()
        conn.prepareStatement(existsSql).use { existsStmt ->
            var inserted = 0L
            var updated = 0L
            for (row in rows) {
                val existedBefore = rowExists(existsStmt, importedTargetColumns, row)
                bindRow(stmt, importedTargetColumns, row)
                stmt.executeUpdate()
                if (existedBefore) updated++ else inserted++
            }
            return WriteResult(
                rowsInserted = inserted,
                rowsUpdated = updated,
                rowsSkipped = 0,
            )
        }
    }

    private fun buildPkExistsSql(): String {
        val whereClause = primaryKeyColumns.joinToString(" AND ") {
            "${quoteSqliteIdentifier(it)} = ?"
        }
        return "SELECT 1 FROM ${qualifiedTable.quotedPath()} WHERE $whereClause LIMIT 1"
    }

    private fun rowExists(
        existsStmt: PreparedStatement,
        importedTargetColumns: List<TargetColumn>,
        row: Array<Any?>,
    ): Boolean {
        primaryKeyColumns.forEachIndexed { index, primaryKeyColumn ->
            val importedIndex = importedTargetColumns.indexOfFirst { it.name == primaryKeyColumn }
            check(importedIndex >= 0) {
                "Missing primary key column '$primaryKeyColumn' in imported columns for table '$table'"
            }
            existsStmt.setObject(index + 1, row[importedIndex])
        }
        existsStmt.executeQuery().use { rs ->
            return rs.next()
        }
    }

    private fun batchWriteResult(
        counts: IntArray,
        skipMode: Boolean,
    ): WriteResult {
        var inserted = 0L
        var skipped = 0L
        var unknown = 0L
        for (count in counts) {
            when (count) {
                Statement.SUCCESS_NO_INFO -> {
                    if (skipMode) unknown++ else inserted++
                }
                0 -> if (skipMode) skipped++ else Unit
                else -> inserted++
            }
        }
        return WriteResult(
            rowsInserted = inserted,
            rowsUpdated = 0,
            rowsSkipped = skipped,
            rowsUnknown = unknown,
        )
    }
}
