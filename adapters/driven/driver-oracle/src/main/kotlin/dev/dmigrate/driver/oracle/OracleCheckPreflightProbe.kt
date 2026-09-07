package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.JdbcCheckPreflightProbe
import java.sql.Connection

/**
 * Der Oracle-Einstieg in die CHECK-Preflight-Sonde.
 *
 * Fuer Oracle ist sie kein Komfort: Oracle validiert einen hinzugefuegten
 * Constraint per Default gegen den Bestand (`ORA-02293`), und der Diff-Pfad
 * rendert bewusst kein `ENABLE NOVALIDATE` — das waere eine stille
 * Abschwaechung. Ohne Sonde erfuehre der Anwender den Konflikt erst, wenn
 * das Apply mitten im Lauf abbricht.
 *
 * Die Schleife und ihre Fehlerbehandlung liegen in
 * [JdbcCheckPreflightProbe]; hier kommt nur der Dialekt dazu.
 */
object OracleCheckPreflightProbe {

    fun probe(connection: DatabaseConnection, diff: DiffResult): List<CheckPreflightDeclaration> =
        probe(connection.asJdbc(), diff)

    fun probe(connection: Connection, diff: DiffResult): List<CheckPreflightDeclaration> =
        JdbcCheckPreflightProbe.probe(DatabaseDialect.ORACLE, connection, diff)
}
