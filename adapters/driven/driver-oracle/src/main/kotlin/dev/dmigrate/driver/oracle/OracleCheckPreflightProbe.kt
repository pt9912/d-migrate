package dev.dmigrate.driver.oracle

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
 * Prueft vor dem Apply, ob ein neuer CHECK gegen die BESTEHENDEN Daten
 * ueberhaupt haelt — das Oracle-Gegenstueck zu den Proben der vier anderen
 * Dialekte.
 *
 * Fuer Oracle ist das kein Komfort: Oracle validiert einen hinzugefuegten
 * Constraint per Default gegen den Bestand (`ORA-02293`), und der
 * Diff-Pfad rendert bewusst kein `ENABLE NOVALIDATE` — das waere eine
 * stille Abschwaechung. Ohne Probe erfuehre der Anwender den Konflikt erst,
 * wenn das Apply mitten im Lauf abbricht.
 *
 * Die Abfrage baut der geteilte [CheckPreflightPlanner]; hier kommen nur
 * der Dialekt und sein Quoting dazu. Sie ist lesend.
 */
object OracleCheckPreflightProbe {

    fun probe(connection: DatabaseConnection, diff: DiffResult): List<CheckPreflightDeclaration> =
        probe(connection.asJdbc(), diff)

    fun probe(connection: Connection, diff: DiffResult): List<CheckPreflightDeclaration> {
        val plan = CheckPreflightPlanner.plan(
            diff = diff,
            dialect = DatabaseDialect.ORACLE.name.lowercase(),
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_POLICY,
            identifierQuoter = { SqlIdentifiers.quoteIdentifier(it, DatabaseDialect.ORACLE) },
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
