package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.data.AbstractJdbcDataReader

/**
 * MySQL [dev.dmigrate.driver.data.DataReader].
 *
 * LF-008 / LN-009 / LN-010: MySQL-Spezifika fuer verlustfreies,
 * konsistentes Export-Streaming:
 * - **Streaming-Strategie**: serverseitiger Cursor via `useCursorFetch=true`
 *   (gesetzt in [dev.dmigrate.driver.connection.HikariConnectionPoolFactory]
 *   als Default in der JDBC-URL) + realer `fetchSize`. Bewusste Wahl gegen
 *   das alte `Statement.setFetchSize(Integer.MIN_VALUE)`-Idiom:
 *   - row-by-row Protokoll-Overhead bei MIN_VALUE
 *   - inkompatibel mit langlaufenden HikariCP-Connections
 *   - in Connector/J 9.x ist `useCursorFetch` der dokumentierte Default-Pfad
 * - Quoting: Backticks, mit Backtick-Escape.
 * - `setAutoCommit(false)` ist mit `useCursorFetch` nicht zwingend nötig —
 *   der serverseitige Cursor steht für sich. Wir lassen es auf `false`,
 *   damit ein evtl. konsistenter Snapshot über den Stream hinweg möglich ist.
 *
 * Tests laufen im `@Tag("integration")`-Workflow gegen einen Testcontainers-
 * MySQL — siehe `.github/workflows/integration.yml`.
 */
class MysqlDataReader : AbstractJdbcDataReader() {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    override fun quoteIdentifier(name: String): String =
        SqlIdentifiers.quoteIdentifier(name, dialect)

    /** LF-008 / LN-010: Tuning fuer serverseitigen Cursor. */
    override val fetchSize: Int = 1_000

    /** Konsistenter Snapshot über den Stream hinweg. */
    override val needsAutoCommitFalse: Boolean = true
}
