package dev.dmigrate.driver.postgresql

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
 * for PostgreSQL targets. Mirrors [SqliteCastPreflightProbe] in
 * shape: read-only counting probe per `AddConstraint(CHECK)` op,
 * returns one [CheckPreflightDeclaration] per op with PASSED /
 * FAILED / PROBE_RUNTIME_ERROR status.
 *
 * The probe quotes identifiers via [SqlIdentifiers.quoteIdentifier]
 * (double-quote shape) which matches the planner's probe-SQL hash
 * input. Mismatches between planner and probe quoting would corrupt
 * the binding-key chain — the renderer would not find the matching
 * declaration at emission time.
 */
object PostgresCheckPreflightProbe {

    /**
     * Public entry point. Iterates every `AddConstraint(CHECK)` op
     * in [diff], runs the count query, returns a declaration per op.
     *
     * Per-op exceptions are caught and yield a
     * [CheckPreflightStatus.PROBE_RUNTIME_ERROR] declaration with
     * the exception message stamped into
     * [CheckPreflightDeclaration.problem]. The probe never throws
     * out of [probe] — the runner / stage treats every declaration
     * uniformly.
     */
    fun probe(connection: Connection, diff: DiffResult): List<CheckPreflightDeclaration> {
        val plan = CheckPreflightPlanner.plan(
            diff = diff,
            dialect = DatabaseDialect.POSTGRESQL.name.lowercase(),
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_POLICY,
            identifierQuoter = { SqlIdentifiers.quoteIdentifier(it, DatabaseDialect.POSTGRESQL) },
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
