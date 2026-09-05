package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.metadata.JdbcOperations

/**
 * Oracle identifier helpers: current schema resolution. Oracle hat kein
 * von der Schema-Ebene getrenntes "Datenbank"-Konzept -- Schema = User.
 */
internal object OracleIdentifiers {

    /** Aktuelles Schema (= aktueller User) der Verbindung. */
    fun currentSchema(session: JdbcOperations): String =
        session.querySingle("SELECT SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS schema_name FROM DUAL")
            ?.get("schema_name") as? String
            ?: error("could not resolve current Oracle schema via SYS_CONTEXT")

    fun quote(name: String): String = SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.ORACLE)
}
