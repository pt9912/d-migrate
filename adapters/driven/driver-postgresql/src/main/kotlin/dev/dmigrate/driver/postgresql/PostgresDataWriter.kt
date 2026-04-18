package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.core.data.DataChunk
import dev.dmigrate.core.data.ImportSchemaMismatchException
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.FinishTableResult
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSetMetaData
import java.sql.Statement
import java.sql.Types

class PostgresDataWriter(
    private val jdbcFactory: (Connection) -> JdbcOperations = ::JdbcMetadataSession,
) : DataWriter {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun schemaSync(): SchemaSync = PostgresSchemaSync(jdbcFactory)

    override fun openTable(
        pool: ConnectionPool,
        table: String,
        options: ImportOptions,
    ): TableImportSession {
        if (options.disableFkChecks) {
            throw UnsupportedOperationException(
                "PostgreSQL does not support generic disableFkChecks in 0.4.0; " +
                    "use schema ordering or DEFERRABLE constraints instead"
            )
        }
        val conn = pool.borrow()
        val jdbc = jdbcFactory(conn)
        val sync = schemaSync()
        val qualified = parseQualifiedTableName(table)
        var triggersDisabled = false
        var savedAutoCommit: Boolean? = null
        try {
            savedAutoCommit = conn.autoCommit
            val targetColumns = loadTargetColumns(conn, qualified)
            val generatedAlwaysColumns = loadGeneratedAlwaysColumns(jdbc, conn, qualified)
            val primaryKeyColumns = if (options.onConflict == OnConflict.UPDATE) {
                loadPrimaryKeyColumns(conn, qualified).also {
                    require(it.isNotEmpty()) {
                        "Target table '$table' has no primary key; onConflict=update requires a primary key"
                    }
                }
            } else {
                emptyList()
            }

            // §6.14 non-atomic truncate: runs before the import transaction
            // so the table stays empty even on failure. RESTART IDENTITY
            // resets attached sequences to their start values.
            if (options.truncate) {
                if (!conn.autoCommit) conn.autoCommit = true
                jdbc.execute("TRUNCATE TABLE ${qualified.quotedPath()} RESTART IDENTITY CASCADE")
            }

            conn.autoCommit = false
            when (options.triggerMode) {
                TriggerMode.FIRE -> Unit
                TriggerMode.STRICT -> {
                    sync.assertNoUserTriggers(conn, table)
                    conn.commit()
                }
                TriggerMode.DISABLE -> {
                    sync.disableTriggers(conn, table)
                    triggersDisabled = true
                }
            }

            val session = PostgresTableImportSession(
                conn = conn,
                savedAutoCommit = savedAutoCommit,
                table = table,
                qualifiedTable = qualified,
                targetColumns = targetColumns,
                generatedAlwaysColumns = generatedAlwaysColumns,
                primaryKeyColumns = primaryKeyColumns,
                options = options,
                schemaSync = sync,
                triggersDisabled = triggersDisabled,
            )
            if (options.truncate) {
                session.markTruncatePerformed()
            }
            return session
        } catch (t: Throwable) {
            try {
                if (triggersDisabled) {
                    sync.enableTriggers(conn, table)
                }
            } catch (cleanup: Throwable) {
                t.addSuppressed(cleanup)
            }
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
                conn.close()
            } catch (cleanup: Throwable) {
                t.addSuppressed(cleanup)
            }
            throw t
        }
    }

    private fun loadTargetColumns(
        conn: Connection,
        table: QualifiedTableName,
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

    private fun loadGeneratedAlwaysColumns(
        jdbc: JdbcOperations,
        conn: Connection,
        table: QualifiedTableName,
    ): Set<String> {
        val schema = table.schemaOrCurrent(conn)
        return jdbc.queryList(
            """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = ?
              AND table_name = ?
              AND is_identity = 'YES'
              AND identity_generation = 'ALWAYS'
            """.trimIndent(),
            schema, table.table,
        ).mapTo(mutableSetOf()) { it["column_name"] as String }
    }

    private fun loadPrimaryKeyColumns(
        conn: Connection,
        table: QualifiedTableName,
    ): List<String> {
        val schema = table.schemaOrCurrent(conn)
        val rows = mutableListOf<Pair<Short, String>>()
        conn.metaData.getPrimaryKeys(conn.catalog, schema, table.table).use { rs ->
            while (rs.next()) {
                rows += rs.getShort("KEY_SEQ") to rs.getString("COLUMN_NAME")
            }
        }
        return rows.sortedBy { it.first }.map { it.second }
    }
}

internal class PostgresTableImportSession(
    private val conn: Connection,
    private val savedAutoCommit: Boolean,
    private val table: String,
    private val qualifiedTable: QualifiedTableName,
    override val targetColumns: List<TargetColumn>,
    private val generatedAlwaysColumns: Set<String>,
    private val primaryKeyColumns: List<String>,
    private val options: ImportOptions,
    private val schemaSync: SchemaSync,
    private var triggersDisabled: Boolean,
) : TableImportSession {

    private enum class State { OPEN, WRITTEN, FAILED, FINISHED, CLOSED }

    private val targetColumnsByName = targetColumns.associateBy { it.name }
    private var state: State = State.OPEN
    private var hasWritten: Boolean = false
    private var truncatePerformed: Boolean = false
    private var triggersReenabled: Boolean = false
    private var importedColumns: List<ColumnDescriptor>? = null
    private var preparedStatement: PreparedStatement? = null
    private var importedTargetColumns: List<TargetColumn>? = null
    private var lastFailure: Throwable? = null

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
                else -> executeInsertChunk(plan, chunk.rows)
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
            "markTruncatePerformed() requires OPEN before any write, " +
                "current state: $state, hasWritten: $hasWritten"
        }
        truncatePerformed = true
    }

    override fun finishTable(): FinishTableResult {
        requireState(State.OPEN, "finishTable")
        return try {
            val adjustments = if (options.reseedSequences) {
                schemaSync.reseedGenerators(conn, table, importedColumns.orEmpty())
            } else {
                emptyList()
            }

            val enableFailure = if (triggersDisabled && !triggersReenabled) {
                runCatching {
                    schemaSync.enableTriggers(conn, table)
                    triggersReenabled = true
                }.exceptionOrNull()
            } else {
                null
            }

            state = State.FINISHED
            if (enableFailure == null) {
                FinishTableResult.Success(adjustments)
            } else {
                lastFailure = enableFailure
                FinishTableResult.PartialFailure(adjustments, enableFailure)
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
            if (triggersDisabled && !triggersReenabled) {
                runCatching {
                    schemaSync.enableTriggers(conn, table)
                    triggersReenabled = true
                }.onFailure(::recordCleanupFailure)
            }
        } finally {
            runCatching { preparedStatement?.close() }.onFailure(::recordCleanupFailure)
            runCatching { conn.autoCommit = savedAutoCommit }.onFailure(::recordCleanupFailure)
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

        val sql = buildInsertSql(resolvedTargetColumns)
        preparedStatement = conn.prepareStatement(sql)
        importedColumns = chunk.columns
        importedTargetColumns = resolvedTargetColumns
        return resolvedTargetColumns
    }

    private fun buildInsertSql(importedTargetColumns: List<TargetColumn>): String {
        val overridingSystemValue = if (importedTargetColumns.any { it.name in generatedAlwaysColumns }) {
            " OVERRIDING SYSTEM VALUE"
        } else {
            ""
        }

        return if (importedTargetColumns.isEmpty()) {
            val baseInsert =
                "INSERT INTO ${qualifiedTable.quotedPath()}$overridingSystemValue DEFAULT VALUES"
            when (options.onConflict) {
                OnConflict.ABORT -> baseInsert
                OnConflict.SKIP -> "$baseInsert ON CONFLICT DO NOTHING"
                OnConflict.UPDATE -> "$baseInsert${buildUpsertClause(importedTargetColumns)} RETURNING (xmax = 0) AS inserted"
            }
        } else {
            val columnList = importedTargetColumns.joinToString(", ") { quotePostgresIdentifier(it.name) }
            val placeholders = importedTargetColumns.joinToString(", ") { "?" }
            val baseInsert =
                "INSERT INTO ${qualifiedTable.quotedPath()} ($columnList)$overridingSystemValue VALUES ($placeholders)"
            when (options.onConflict) {
                OnConflict.UPDATE -> "$baseInsert${buildUpsertClause(importedTargetColumns)} RETURNING (xmax = 0) AS inserted"
                OnConflict.ABORT -> baseInsert
                OnConflict.SKIP -> "$baseInsert ON CONFLICT DO NOTHING"
            }
        }
    }

    private fun buildUpsertClause(importedTargetColumns: List<TargetColumn>): String {
        val pkSet = primaryKeyColumns.toSet()
        val updateColumns = importedTargetColumns.filterNot { it.name in pkSet }
        if (primaryKeyColumns.isEmpty()) {
            error("ON CONFLICT UPDATE requires primaryKeyColumns to be loaded")
        }
        val conflictTarget = primaryKeyColumns.joinToString(", ") { quotePostgresIdentifier(it) }
        if (updateColumns.isEmpty()) {
            val pk = quotePostgresIdentifier(primaryKeyColumns.first())
            return " ON CONFLICT ($conflictTarget) DO UPDATE SET $pk = EXCLUDED.$pk"
        }
        val assignments = updateColumns.joinToString(", ") {
            "${quotePostgresIdentifier(it.name)} = EXCLUDED.${quotePostgresIdentifier(it.name)}"
        }
        return " ON CONFLICT ($conflictTarget) DO UPDATE SET $assignments"
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

    private fun bindRow(
        stmt: PreparedStatement,
        importedTargetColumns: List<TargetColumn>,
        row: Array<Any?>,
    ) {
        importedTargetColumns.forEachIndexed { index, targetColumn ->
            bindValue(stmt, index + 1, targetColumn, row[index])
        }
    }

    private fun executeInsertChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        val stmt = preparedStatement!!
        for (row in rows) {
            bindRow(stmt, importedTargetColumns, row)
            stmt.addBatch()
        }
        return toWriteResult(stmt.executeBatch())
    }

    private fun executeUpsertChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult {
        val stmt = preparedStatement!!
        var inserted = 0L
        var updated = 0L
        for (row in rows) {
            bindRow(stmt, importedTargetColumns, row)
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "UPSERT RETURNING returned no row for table '$table'" }
                if (rs.getBoolean(1)) inserted++ else updated++
            }
        }
        return WriteResult(
            rowsInserted = inserted,
            rowsUpdated = updated,
            rowsSkipped = 0,
        )
    }

    private fun bindValue(
        stmt: PreparedStatement,
        parameterIndex: Int,
        targetColumn: TargetColumn,
        value: Any?,
    ) {
        if (value == null) {
            stmt.setNull(parameterIndex, targetColumn.jdbcType)
            return
        }

        when {
            targetColumn.jdbcType == Types.OTHER &&
                targetColumn.sqlTypeName.equals("json", ignoreCase = true) ->
                stmt.setObject(parameterIndex, pgObject("json", value.toString()))

            targetColumn.jdbcType == Types.OTHER &&
                targetColumn.sqlTypeName.equals("jsonb", ignoreCase = true) ->
                stmt.setObject(parameterIndex, pgObject("jsonb", value.toString()))

            targetColumn.jdbcType == Types.OTHER &&
                targetColumn.sqlTypeName.equals("interval", ignoreCase = true) ->
                stmt.setObject(parameterIndex, pgObject("interval", value.toString()))

            targetColumn.jdbcType == Types.OTHER &&
                targetColumn.sqlTypeName.equals("xml", ignoreCase = true) ->
                stmt.setObject(parameterIndex, pgObject("xml", value.toString()))

            targetColumn.jdbcType == Types.ARRAY && value is List<*> ->
                stmt.setArray(
                    parameterIndex,
                    stmt.connection.createArrayOf(arrayElementType(targetColumn.sqlTypeName), value.toTypedArray())
                )

            else -> stmt.setObject(parameterIndex, value)
        }
    }

    private fun arrayElementType(sqlTypeName: String?): String {
        if (sqlTypeName == null) return "text"
        return when {
            sqlTypeName.endsWith("[]") -> sqlTypeName.removeSuffix("[]")
            sqlTypeName.startsWith("_") -> sqlTypeName.removePrefix("_")
            else -> sqlTypeName
        }
    }

    private fun pgObject(type: String, value: String): PGobject =
        PGobject().apply {
            this.type = type
            this.value = value
        }

    private fun toWriteResult(counts: IntArray): WriteResult {
        var inserted = 0L
        var skipped = 0L
        var unknown = 0L

        for (count in counts) {
            when (count) {
                Statement.SUCCESS_NO_INFO -> unknown++
                0 -> skipped++
                else -> inserted += count.toLong()
            }
        }

        return WriteResult(
            rowsInserted = inserted,
            rowsUpdated = 0,
            rowsSkipped = skipped,
            rowsUnknown = unknown,
        )
    }

    private fun requireState(expected: State, operation: String) {
        check(state == expected) {
            "$operation requires $expected, current state: $state"
        }
    }

    private fun recordCleanupFailure(t: Throwable) {
        lastFailure?.addSuppressed(t)
    }
}
