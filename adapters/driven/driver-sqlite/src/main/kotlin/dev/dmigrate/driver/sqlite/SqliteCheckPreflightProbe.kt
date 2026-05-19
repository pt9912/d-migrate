package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.CheckPreflightPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import java.sql.Connection
import java.sql.SQLException

/**
 * F.5 Sub-Slice E.4 (2026-05-19): live-data CHECK preflight probe
 * for SQLite targets. Mirrors [PostgresCheckPreflightProbe] / the
 * MySQL counterpart. The probe is read-only and runs on the source
 * table before the rebuild-pipeline kicks off.
 *
 * Identifier quoting uses double quotes via the
 * [DatabaseDialect.SQLITE] branch of [SqlIdentifiers].
 */
object SqliteCheckPreflightProbe {

    fun probe(connection: Connection, diff: DiffResult): List<CheckPreflightDeclaration> {
        val plan = CheckPreflightPlanner.plan(
            diff = diff,
            dialect = DatabaseDialect.SQLITE.name.lowercase(),
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_POLICY,
            identifierQuoter = { SqlIdentifiers.quoteIdentifier(it, DatabaseDialect.SQLITE) },
        )
        return plan.map { planned ->
            try {
                val failingRows = countViolations(connection, planned.probeSql)
                CheckPreflightDeclaration(
                    operationId = planned.operationId,
                    dialect = planned.dialect,
                    table = planned.table,
                    constraintName = planned.constraintName,
                    expression = planned.expression,
                    status = if (failingRows == 0L) CheckPreflightStatus.PASSED else CheckPreflightStatus.FAILED,
                    sqlHash = planned.sqlHash,
                    failingRows = failingRows.takeIf { it > 0 },
                )
            } catch (e: SQLException) {
                CheckPreflightDeclaration(
                    operationId = planned.operationId,
                    dialect = planned.dialect,
                    table = planned.table,
                    constraintName = planned.constraintName,
                    expression = planned.expression,
                    status = CheckPreflightStatus.PROBE_RUNTIME_ERROR,
                    sqlHash = planned.sqlHash,
                    problem = e.message ?: e::class.simpleName.orEmpty(),
                )
            }
        }
    }

    private fun countViolations(connection: Connection, sql: String): Long =
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
}
