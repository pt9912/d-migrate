package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.SqliteCastPreflightDeclaration
import dev.dmigrate.driver.SqliteCastPreflightStatus
import java.sql.Connection

/**
 * Plan-2 §B.2 live-data preflight for whitelisted SQLite RebuildTable
 * casts. The probe is read-only: for every whitelisted
 * `AlterColumnType` absorbed into a SQLite rebuild bucket it counts
 * rows that would not survive the renderer's `CAST(...)` contract
 * cleanly, and returns a declaration consumed by the renderer.
 */
object SqliteCastPreflightProbe {

    fun probe(connection: Connection, diff: DiffResult): List<SqliteCastPreflightDeclaration> =
        SqliteCastPreflightSql.bindingsFor(diff).map { binding ->
            val totalRows = readLong(connection, "SELECT COUNT(*) FROM ${quote(binding.table)};")
            val failingRows = if (binding.failingSql == null) 0L else readLong(connection, binding.countSql)
            SqliteCastPreflightDeclaration(
                operationId = binding.operationId,
                table = binding.table,
                column = binding.column,
                sourceType = binding.sourceTypeText,
                targetType = binding.targetTypeText,
                status = if (failingRows == 0L) {
                    SqliteCastPreflightStatus.PASSED
                } else {
                    SqliteCastPreflightStatus.FAILED
                },
                sqlHash = binding.sqlHash,
                totalRows = totalRows,
                failingRows = failingRows,
                sampleRowIds = emptyList(),
                problem = if (failingRows == 0L) {
                    null
                } else {
                    "SQLite cast preflight found $failingRows row(s) that violate ${binding.table}.${binding.column}"
                },
            )
        }

    private fun readLong(connection: Connection, sql: String): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }

    private fun quote(name: String): String =
        "\"" + name.replace("\"", "\"\"") + "\""
}
