package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.data.AbstractTableImportSession
import dev.dmigrate.driver.data.ImportOptions
import dev.dmigrate.driver.data.OnConflict
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.data.TargetColumn
import dev.dmigrate.driver.data.WriteResult
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import java.sql.Types

internal class PostgresTableImportSession(
    conn: Connection,
    savedAutoCommit: Boolean,
    table: String,
    private val qualifiedTable: QualifiedTableName,
    targetColumns: List<TargetColumn>,
    private val generatedAlwaysColumns: Set<String>,
    primaryKeyColumns: List<String>,
    options: ImportOptions,
    private val schemaSync: PostgresSchemaSync,
    private var triggersDisabled: Boolean,
) : AbstractTableImportSession(conn, savedAutoCommit, table, targetColumns, primaryKeyColumns, options) {

    private var triggersReenabled: Boolean = false

    override fun buildInsertSql(importedTargetColumns: List<TargetColumn>): String {
        val overridingSystemValue = if (importedTargetColumns.any { it.name in generatedAlwaysColumns }) {
            " OVERRIDING SYSTEM VALUE"
        } else {
            ""
        }

        return if (importedTargetColumns.isEmpty()) {
            buildDefaultValuesInsert(overridingSystemValue, importedTargetColumns)
        } else {
            buildColumnInsert(importedTargetColumns, overridingSystemValue)
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

    private fun buildDefaultValuesInsert(
        overridingSystemValue: String,
        importedTargetColumns: List<TargetColumn>,
    ): String {
        val baseInsert =
            "INSERT INTO ${qualifiedTable.quotedPath()}$overridingSystemValue DEFAULT VALUES"
        return when (options.onConflict) {
            OnConflict.ABORT -> baseInsert
            OnConflict.SKIP -> "$baseInsert ON CONFLICT DO NOTHING"
            OnConflict.UPDATE -> buildReturningInsert(baseInsert, buildUpsertClause(importedTargetColumns))
        }
    }

    private fun buildColumnInsert(
        importedTargetColumns: List<TargetColumn>,
        overridingSystemValue: String,
    ): String {
        val columnList = importedTargetColumns.joinToString(", ") { quotePostgresIdentifier(it.name) }
        val placeholders = importedTargetColumns.joinToString(", ") { "?" }
        val baseInsert =
            "INSERT INTO ${qualifiedTable.quotedPath()} ($columnList)$overridingSystemValue VALUES ($placeholders)"
        return when (options.onConflict) {
            OnConflict.UPDATE -> buildReturningInsert(baseInsert, buildUpsertClause(importedTargetColumns))
            OnConflict.ABORT -> baseInsert
            OnConflict.SKIP -> "$baseInsert ON CONFLICT DO NOTHING"
        }
    }

    private fun buildReturningInsert(
        baseInsert: String,
        upsertClause: String = "",
    ): String =
        "$baseInsert$upsertClause RETURNING (xmax = 0) AS inserted"

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
        if (rows.isEmpty()) return WriteResult(rowsInserted = 0, rowsUpdated = 0, rowsSkipped = 0)
        if (importedTargetColumns.isEmpty()) {
            return executeUpsertChunkRowByRow(rows)
        }

        val sql = buildMultiRowUpsertSql(importedTargetColumns, rows.size)
        conn.prepareStatement(sql).use { batchStmt ->
            var paramIdx = 1
            for (row in rows) {
                for ((colIdx, targetColumn) in importedTargetColumns.withIndex()) {
                    bindValue(batchStmt, paramIdx++, targetColumn, row[colIdx])
                }
            }
            var inserted = 0L
            var updated = 0L
            batchStmt.executeQuery().use { rs ->
                while (rs.next()) {
                    if (rs.getBoolean(1)) inserted++ else updated++
                }
            }
            return WriteResult(rowsInserted = inserted, rowsUpdated = updated, rowsSkipped = 0)
        }
    }

    private fun executeUpsertChunkRowByRow(rows: List<Array<Any?>>): WriteResult {
        val stmt = preparedStatement!!
        var inserted = 0L
        var updated = 0L
        for (row in rows) {
            stmt.executeQuery().use { rs ->
                check(rs.next()) { "UPSERT RETURNING returned no row for table '$table'" }
                if (rs.getBoolean(1)) inserted++ else updated++
            }
        }
        return WriteResult(rowsInserted = inserted, rowsUpdated = updated, rowsSkipped = 0)
    }

    private fun buildMultiRowUpsertSql(importedTargetColumns: List<TargetColumn>, rowCount: Int): String {
        val overridingSystemValue = if (importedTargetColumns.any { it.name in generatedAlwaysColumns }) {
            " OVERRIDING SYSTEM VALUE"
        } else {
            ""
        }
        val columnList = importedTargetColumns.joinToString(", ") { quotePostgresIdentifier(it.name) }
        val singleRow = "(${importedTargetColumns.joinToString(", ") { "?" }})"
        val allRows = (1..rowCount).joinToString(", ") { singleRow }
        val baseInsert =
            "INSERT INTO ${qualifiedTable.quotedPath()} " +
                "($columnList)$overridingSystemValue VALUES $allRows"
        return buildReturningInsert(baseInsert, buildUpsertClause(importedTargetColumns))
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
