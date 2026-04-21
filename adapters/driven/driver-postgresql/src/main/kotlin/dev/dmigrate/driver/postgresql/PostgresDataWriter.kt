package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.data.AbstractTableImportSession
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.SchemaSync
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TableImportSession
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.TriggerMode
import dev.dmigrate.driver.data.WriteResult
import dev.dmigrate.driver.metadata.JdbcMetadataSession
import dev.dmigrate.driver.metadata.JdbcOperations
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.PreparedStatement
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
    ): List<TargetColumn> = dev.dmigrate.driver.data.loadTargetColumns(conn, table.quotedPath())

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
    conn: Connection,
    savedAutoCommit: Boolean,
    table: String,
    private val qualifiedTable: QualifiedTableName,
    targetColumns: List<TargetColumn>,
    private val generatedAlwaysColumns: Set<String>,
    primaryKeyColumns: List<String>,
    options: ImportOptions,
    private val schemaSync: SchemaSync,
    private var triggersDisabled: Boolean,
) : AbstractTableImportSession(conn, savedAutoCommit, table, targetColumns, primaryKeyColumns, options) {

    private var triggersReenabled: Boolean = false

    // ─── Dialect hooks ──────────────────────────────────────────────────

    override fun buildInsertSql(importedTargetColumns: List<TargetColumn>): String {
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

    override fun executeChunk(
        importedTargetColumns: List<TargetColumn>,
        rows: List<Array<Any?>>,
    ): WriteResult = when (options.onConflict) {
        OnConflict.UPDATE -> executeUpsertChunk(importedTargetColumns, rows)
        else -> executeInsertChunk(importedTargetColumns, rows)
    }

    override fun bindRow(
        stmt: PreparedStatement,
        importedTargetColumns: List<TargetColumn>,
        row: Array<Any?>,
    ) {
        importedTargetColumns.forEachIndexed { index, targetColumn ->
            bindValue(stmt, index + 1, targetColumn, row[index])
        }
    }

    override fun reseedSequences(): List<SequenceAdjustment> =
        schemaSync.reseedGenerators(conn, table, importedColumns.orEmpty())

    override fun finishDialectCleanup(): Throwable? =
        if (triggersDisabled && !triggersReenabled) {
            runCatching {
                schemaSync.enableTriggers(conn, table)
                triggersReenabled = true
            }.exceptionOrNull()
        } else {
            null
        }

    override fun closePreFinally() {
        if (triggersDisabled && !triggersReenabled) {
            runCatching {
                schemaSync.enableTriggers(conn, table)
                triggersReenabled = true
            }.onFailure(::recordCleanupFailure)
        }
    }

    override fun closeFinally() {
        runCatching { conn.autoCommit = savedAutoCommit }.onFailure(::recordCleanupFailure)
    }

    /**
     * PostgreSQL does not need the PK-columns-in-import check because the
     * ON CONFLICT clause references PK columns from table metadata, not
     * from the imported data.
     */
    override fun validateUpsertColumns(resolvedTargetColumns: List<TargetColumn>) {}

    // ─── PostgreSQL-specific execution strategies ──────────��────────────

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
}
