package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.data.AbstractJdbcDataReader

/**
 * PostgreSQL [dev.dmigrate.driver.data.DataReader].
 *
 * LF-008 / LN-009 / LN-010: PostgreSQL-Spezifika:
 * - Cursor-basiertes Streaming via `Statement#setFetchSize(N)` +
 *   `setAutoCommit(false)` — beides setzt der [AbstractJdbcDataReader]
 *   automatisch, weil [needsAutoCommitFalse] hier `true` bleibt.
 * - Quoting: doppelte Anführungszeichen, Escape eingebetteter Quotes.
 * - `application_name=d-migrate` wird zentral in
 *   [dev.dmigrate.driver.connection.HikariConnectionPoolFactory] über die
 *   `ApplicationName`-URL-Property gesetzt.
 *
 * Tests laufen im `@Tag("integration")`-Workflow gegen einen Testcontainers-
 * PostgreSQL — siehe `.github/workflows/integration.yml`.
 */
class PostgresDataReader : AbstractJdbcDataReader() {

    override val dialect: DatabaseDialect = DatabaseDialect.POSTGRESQL

    override fun quoteIdentifier(name: String): String =
        SqlIdentifiers.quoteIdentifier(name, dialect)

    /** Standard-Cursor-fetchSize. Empirisch guter Wert für PostgreSQL JDBC. */
    override val fetchSize: Int = 1_000

    /** PostgreSQL braucht zwingend `setAutoCommit(false)` für Cursor-Streaming. */
    override val needsAutoCommitFalse: Boolean = true
}
