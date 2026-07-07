package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.migration.CheckPreflightPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.asJdbc
import java.sql.Connection
import java.sql.SQLException

/**
 * F.5 Sub-Slice E.4 (2026-05-19): live-data CHECK preflight probe
 * for MySQL / MariaDB targets. Identical shape to
 * [PostgresCheckPreflightProbe] except identifier quoting uses
 * backticks (`MYSQL` dialect on [SqlIdentifiers]).
 *
 * The preflight gate's enforcement-capability check
 * (`MysqlCheckEnforcementResolver`) fires *before* the probe runs,
 * so this probe should only be invoked when the runner has already
 * proved the live server enforces CHECK semantics. Calling it on
 * pre-8.0.16 MySQL / pre-10.2.1 MariaDB is still safe (the count
 * query is a plain SELECT) but the result has no semantic weight.
 */
object MysqlCheckPreflightProbe {

    fun probe(connection: DatabaseConnection, diff: DiffResult): List<CheckPreflightDeclaration> =
        probe(connection.asJdbc(), diff)

    fun probe(connection: Connection, diff: DiffResult): List<CheckPreflightDeclaration> {
        val plan = CheckPreflightPlanner.plan(
            diff = diff,
            dialect = DatabaseDialect.MYSQL.name.lowercase(),
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_POLICY,
            identifierQuoter = { SqlIdentifiers.quoteIdentifier(it, DatabaseDialect.MYSQL) },
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
