package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.migration.CheckPreflightPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.CheckPreflightStatus
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import java.sql.Connection
import java.sql.SQLException

/**
 * Fuehrt die CHECK-Preflight-Sonde aus: pro geplantem CHECK eine lesende
 * Zaehlabfrage gegen den Bestand, damit ein Constraint, den die vorhandenen
 * Daten verletzen, vor dem Apply auffaellt statt mitten darin.
 *
 * Die dialektabhaengige Arbeit macht der geteilte [CheckPreflightPlanner];
 * uebrig bleibt die Ausfuehrungs- und Fehlerbehandlungsschleife, und die ist
 * fuer alle fuenf Dialekte dieselbe. Sie stand einmal fuenfmal zeichengleich
 * im Repo — jede Aenderung an der Fehlerbehandlung war damit eine Aenderung
 * an fuenf Stellen, und eine zu vergessen fiel in keinem Test auf, weil jede
 * Fassung ihren eigenen Dialekt-Test hatte.
 *
 * Die fuenf `<Dialekt>CheckPreflightProbe`-Objekte bleiben als Einstiegspunkte
 * bestehen: die CLI verdrahtet sie namentlich.
 */
object JdbcCheckPreflightProbe {

    fun probe(
        dialect: DatabaseDialect,
        connection: Connection,
        diff: DiffResult,
    ): List<CheckPreflightDeclaration> {
        val plan = CheckPreflightPlanner.plan(
            diff = diff,
            dialect = dialect.name.lowercase(),
            initialStatus = CheckPreflightPlanner.InitialStatus.NOT_RUN_POLICY,
            identifierQuoter = { SqlIdentifiers.quoteIdentifier(it, dialect) },
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
