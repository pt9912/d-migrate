package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Port: baut die finale JDBC-URL für einen Dialekt aus einer
 * [ConnectionConfig]. Wird vom [HikariConnectionPoolFactory] verwendet.
 *
 * Plan §3.2 / §3.3 / §3.4: jeder Treiber bringt einen eigenen `*JdbcUrlBuilder`
 * mit, der dialekt-spezifische Default-Parameter (PostgreSQL `ApplicationName`,
 * MySQL `useCursorFetch`, SQLite `journal_mode`/`foreign_keys`) injiziert,
 * ohne explizit gesetzte User-Werte zu überschreiben.
 *
 * Implementierungen liegen in den Treiber-Modulen
 * (`d-migrate-driver-{postgresql,mysql,sqlite}`) und werden über die
 * [JdbcUrlBuilderRegistry] zur Laufzeit aufgelöst.
 */
interface JdbcUrlBuilder {
    val dialect: DatabaseDialect

    /**
     * Default-Parameter, die in die JDBC-URL injiziert werden, sofern der
     * User sie nicht bereits in `config.params` gesetzt hat.
     */
    fun defaultParams(): Map<String, String>

    /**
     * Baut die finale JDBC-URL.
     *
     * Default-Implementierung: scheme + host/port/database aus dem Dialekt-
     * Mapping ableiten, dann `defaultParams()` mit `config.params` mergen
     * (User-Werte überschreiben Defaults) und URL-encoded anhängen.
     */
    fun buildJdbcUrl(config: ConnectionConfig): String {
        require(config.dialect == dialect) {
            "JdbcUrlBuilder for $dialect cannot build URL for ${config.dialect}"
        }
        val merged = LinkedHashMap<String, String>().apply {
            putAll(defaultParams())
            putAll(config.params)
        }
        // SQLite treats `jdbc:sqlite::memory:?...` as a literal file path
        // instead of an in-memory database. As soon as query parameters are
        // present we must switch to the URI form `file::memory:?...`.
        val base = if (
            dialect == DatabaseDialect.SQLITE &&
            config.database == ":memory:" &&
            merged.isNotEmpty()
        ) {
            "jdbc:sqlite:file::memory:"
        } else {
            baseJdbcUrl(config)
        }
        val params = if (merged.isEmpty()) {
            ""
        } else {
            "?" + merged.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        }
        return base + params
    }

    /** Treiber-spezifisches Schema + host/port/database (ohne Query-Params). */
    fun baseJdbcUrl(config: ConnectionConfig): String

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}
