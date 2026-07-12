package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.JdbcUrlBuilder
import dev.dmigrate.driver.connection.SslMode
import dev.dmigrate.driver.connection.SslSettings

/**
 * MySQL [JdbcUrlBuilder].
 *
 * LF-008 / LN-009 / LN-010: Default-Parameter fuer MySQL-Streaming:
 * - `useCursorFetch=true` — serverseitiger Cursor für sauberes Streaming.
 *   **Bewusst gegen `Statement.setFetchSize(Integer.MIN_VALUE)`** wegen
 *   row-by-row Protokoll-Overhead und HikariCP-Inkompatibilität.
 *
 * Security-by-default:
 * - `allowPublicKeyRetrieval` wird **nicht** implizit aktiviert.
 *   Falls ein Non-TLS-Setup mit `caching_sha2_password` es benötigt, muss der
 *   Parameter explizit über `ConnectionConfig.params` gesetzt werden.
 *
 * Fidelity-by-default:
 * - `yearIsDateType=false` — Connector/J liefert `YEAR`-Spalten sonst als
 *   `java.sql.Date` (`2006` → `2006-01-01`), was beim Daten-Transfer den Jahres-
 *   wert korrumpiert (Finding Y1, sample-db-phase2-findings.md). Mit `false`
 *   kommt `YEAR` als numerischer Wert (`2006`) zurück. Betrifft **nur** `YEAR`,
 *   nicht `DATE`/`DATETIME`/`TIMESTAMP`.
 */
class MysqlJdbcUrlBuilder : JdbcUrlBuilder {

    override val dialect: DatabaseDialect = DatabaseDialect.MYSQL

    // Connector/J 9.x hat `useUnicode` und `characterEncoding` entfernt —
    // der Connector nutzt jetzt automatisch das server-seitige Character-Set.
    override fun defaultParams(): Map<String, String> = mapOf(
        "useCursorFetch" to "true",
        "rewriteBatchedStatements" to "true",
        "yearIsDateType" to "false",
    )

    override fun baseJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == DatabaseDialect.MYSQL) {
            "MysqlJdbcUrlBuilder cannot build URL for ${config.dialect}"
        }
        val port = config.port ?: 3306
        return "jdbc:mysql://${config.host}:$port/${config.database}"
    }

    // LN-026: neutrales SslMode → Connector/J `sslMode`. `ALLOW` gibt es in MySQL
    // nicht → opportunistisch `PREFERRED`. rootCert (Client-CA) ist Nicht-Scope
    // (Truststore-Tiefenstufe); `VERIFY_*` wirkt voll erst mit Truststore.
    override fun sslParams(ssl: SslSettings): Map<String, String> = buildMap {
        ssl.mode?.let { put("sslMode", it.toSslMode()) }
    }

    private fun SslMode.toSslMode(): String = when (this) {
        SslMode.DISABLE -> "DISABLED"
        SslMode.ALLOW, SslMode.PREFER -> "PREFERRED"
        SslMode.REQUIRE -> "REQUIRED"
        SslMode.VERIFY_CA -> "VERIFY_CA"
        SslMode.VERIFY_FULL -> "VERIFY_IDENTITY"
    }
}
