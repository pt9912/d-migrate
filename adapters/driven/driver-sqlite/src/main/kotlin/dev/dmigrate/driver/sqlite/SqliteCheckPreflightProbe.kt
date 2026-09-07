package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.driver.CheckPreflightDeclaration
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.driver.migration.JdbcCheckPreflightProbe
import java.sql.Connection

/**
 * Der SQLite-Einstieg in die CHECK-Preflight-Sonde.
 *
 * Die Schleife und ihre Fehlerbehandlung liegen in
 * [JdbcCheckPreflightProbe]; hier kommt nur der Dialekt dazu, aus dem der
 * Planer sein Quoting und seinen Sonden-Hash ableitet. Die CLI verdrahtet
 * diesen Einstieg namentlich.
 */
object SqliteCheckPreflightProbe {

    fun probe(connection: DatabaseConnection, diff: DiffResult): List<CheckPreflightDeclaration> =
        probe(connection.asJdbc(), diff)

    fun probe(connection: Connection, diff: DiffResult): List<CheckPreflightDeclaration> =
        JdbcCheckPreflightProbe.probe(DatabaseDialect.SQLITE, connection, diff)
}
