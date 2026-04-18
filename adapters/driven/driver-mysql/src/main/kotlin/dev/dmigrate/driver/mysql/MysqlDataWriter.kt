package dev.dmigrate.driver.mysql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.util.concurrent.Executor

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
                conn.rollback()
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
    ): List<TargetColumn> {
        conn.prepareStatement("SELECT * FROM ${table.quotedPath()} LIMIT 0").use { ps ->
            ps.executeQuery().use { rs ->
                val md = rs.metaData
                return buildList(md.columnCount) {
                    for (i in 1..md.columnCount) {
                        add(
                            TargetColumn(
                                name = md.getColumnLabel(i),
                                nullable = md.isNullable(i) != ResultSetMetaData.columnNoNulls,
                                jdbcType = md.getColumnType(i),
                                sqlTypeName = md.getColumnTypeName(i),
                            )
                        )
                    }
                }
            }
        }
    }

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
    private val conn: Connection,
    private val savedAutoCommit: Boolean,
    private val table: String,
    private val qualifiedTable: MysqlQualifiedTableName,
    override val targetColumns: List<TargetColumn>,
    private val primaryKeyColumns: List<String>,
    private val options: ImportOptions,
    private val schemaSync: MysqlSchemaSync,
    private var fkChecksDisabled: Boolean,
) : TableImportSession {

    private enum class State { OPEN, WRITTEN, FAILED, FINISHED, CLOSED }

    private val targetColumnsByName = targetColumns.associateBy { it.name }
    private var state: State = State.OPEN
    private var hasWritten: Boolean = false
    private var truncatePerformed: Boolean = false
    private var importedColumns: List<ColumnDescriptor>? = null
    private var preparedStatement: PreparedStatement? = null
    private var importedTargetColumns: List<TargetColumn>? = null
    private var lastFailure: Throwable? = null
    private var discardConnection: Boolean = false

    override fun write(chunk: DataChunk): WriteResult {
        requireState(State.OPEN, "write")
        if (chunk.table != table) {
            state = State.FAILED
            val failure = ImportSchemaMismatchException(
                "Chunk table '${chunk.table}' does not match open target table '$table'"
            )
            lastFailure = failure
            throw failure
        }

        return try {
            val plan = ensureInsertPlan(chunk)
            validateRowWidths(chunk, plan.size)
            val result = when (options.onConflict) {
                OnConflict.UPDATE -> executeUpsertChunk(plan, chunk.rows)
                OnConflict.SKIP -> executeSkipChunk(plan, chunk.rows)
                OnConflict.ABORT -> executeBatchChunk(plan, chunk.rows)
            }
            state = State.WRITTEN
            hasWritten = true
            result
        } catch (t: Throwable) {
            state = State.FAILED
            lastFailure = t
            throw t
        }
    }

    override fun commitChunk() {
        requireState(State.WRITTEN, "commitChunk")
        try {
            conn.commit()
            preparedStatement?.clearBatch()
            state = State.OPEN
        } catch (t: Throwable) {
            state = State.FAILED
            lastFailure = t
            throw t
        }
    }

    override fun rollbackChunk() {
        requireState(State.WRITTEN, "rollbackChunk")
        try {
            conn.rollback()
            preparedStatement?.clearBatch()
            state = State.OPEN
        } catch (t: Throwable) {
            state = State.FAILED
            lastFailure = t
            throw t
        }
    }

    override fun markTruncatePerformed() {
        check(state == State.OPEN && !hasWritten) {
            "markTruncatePerformed() requires OPEN before any write, current state: $state, hasWritten: $hasWritten"
        }
        truncatePerformed = true
    }

    override fun finishTable(): FinishTableResult {
        requireState(State.OPEN, "finishTable")
        return try {
            val adjustments = if (options.reseedSequences) {
                schemaSync.reseedGenerators(
                    conn = conn,
                    table = table,
                    importedColumns = importedColumns.orEmpty(),
                    truncatePerformed = truncatePerformed,
                )
            } else {
                emptyList()
            }

            val fkResetFailure = if (fkChecksDisabled) {
                runCatching {
                    MysqlDataWriter.setForeignKeyChecks(conn, enabled = true)
                    fkChecksDisabled = false
                }.exceptionOrNull()
            } else {
                null
            }

            state = State.FINISHED
            if (fkResetFailure == null) {
                FinishTableResult.Success(adjustments)
            } else {
                lastFailure = fkResetFailure
                FinishTableResult.PartialFailure(adjustments, fkResetFailure)
            }
        } catch (t: Throwable) {
            state = State.FAILED
            lastFailure = t
            throw t
        }
    }

    override fun close() {
        if (state == State.CLOSED) return

        try {
            if (state == State.WRITTEN || state == State.OPEN || state == State.FAILED) {
                runCatching { conn.rollback() }.onFailure(::recordCleanupFailure)
            }
        } finally {
            runCatching { preparedStatement?.close() }.onFailure(::recordCleanupFailure)
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
            runCatching { conn.close() }.onFailure(::recordCleanupFailure)
            state = State.CLOSED
        }
    }

    private fun ensureInsertPlan(chunk: DataChunk): List<TargetColumn> {
        importedColumns?.let { existing ->
            if (existing != chunk.columns) {
                throw ImportSchemaMismatchException(
                    "All chunks for table '$table' must use the same column layout; " +
                        "expected ${existing.map { it.name }}, got ${chunk.columns.map { it.name }}"
                )
            }
            return importedTargetColumns!!
        }

        val duplicates = chunk.columns.groupBy { it.name }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw ImportSchemaMismatchException(
                "Chunk for table '$table' contains duplicate columns: ${duplicates.joinToString()}"
            )
        }

        val resolvedTargetColumns = chunk.columns.map { sourceColumn ->
            targetColumnsByName[sourceColumn.name]
                ?: throw ImportSchemaMismatchException(
                    "Target table '$table' has no column '${sourceColumn.name}'"
                )
        }

        if (options.onConflict == OnConflict.UPDATE) {
            val importedNames = resolvedTargetColumns.map { it.name }.toSet()
            val missingPrimaryKeys = primaryKeyColumns.filterNot { it in importedNames }
            if (missingPrimaryKeys.isNotEmpty()) {
                throw ImportSchemaMismatchException(
                    "onConflict=update for table '$table' requires all primary key columns; missing ${missingPrimaryKeys.joinToString()}"
                )
            }
        }

        preparedStatement = conn.prepareStatement(buildInsertSql(resolvedTargetColumns))
        importedColumns = chunk.columns
        importedTargetColumns = resolvedTargetColumns
        return resolvedTargetColumns
    }

    private fun buildInsertSql(importedTargetColumns: List<TargetColumn>): String {
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

    private fun validateRowWidths(chunk: DataChunk, columnCount: Int) {
        chunk.rows.forEachIndexed { rowIndex, row ->
            if (row.size != columnCount) {
                throw ImportSchemaMismatchException(
                    "Chunk row $rowIndex for table '$table' has ${row.size} values, expected $columnCount"
                )
            }
        }
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

    private fun bindRow(
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

    private fun requireState(expected: State, operation: String) {
        check(state == expected) {
            "$operation requires state $expected, current state: $state"
        }
    }

    private fun recordCleanupFailure(t: Throwable) {
        if (lastFailure == null) {
            lastFailure = t
        } else {
            lastFailure?.addSuppressed(t)
        }
    }

    private companion object {
        private val DIRECT_EXECUTOR = Executor { command -> command.run() }
    }
}
