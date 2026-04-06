package dev.dmigrate.driver.connection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import dev.dmigrate.driver.DatabaseDialect
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.sql.Connection

/**
 * Erzeugt einen [ConnectionPool] (HikariCP-basiert) aus einer [ConnectionConfig].
 *
 * Dialekt-spezifische Defaults:
 * - **SQLite**: `maximumPoolSize = 1` (SQLite unterstützt keine parallelen
 *   Schreibzugriffe; siehe `connection-config-spec.md` §2.2)
 * - **PostgreSQL** und **MySQL**: HikariCP-Defaults aus [PoolSettings]
 *
 * **JDBC-URL-Konstruktion**: Phase A baut eine minimale JDBC-URL pro Dialekt
 * direkt hier. In Phase B (DataReader-Implementierung) übernehmen die
 * dialektspezifischen `*JdbcUrlBuilder`-Klassen pro Treibermodul die
 * vollständige Parameter-Behandlung (z.B. `useCursorFetch=true` für MySQL,
 * `application_name` für PostgreSQL).
 */
object HikariConnectionPoolFactory {

    /** Erzeugt einen offenen [ConnectionPool]. Caller MUSS `pool.close()` aufrufen. */
    fun create(config: ConnectionConfig): ConnectionPool {
        val effectivePool = if (config.dialect == DatabaseDialect.SQLITE) {
            // SQLite kann nicht parallel schreiben — Pool auf 1 zwingen
            config.pool.copy(maximumPoolSize = 1, minimumIdle = 1)
        } else {
            config.pool
        }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = buildJdbcUrl(config)
            if (config.user != null) username = config.user
            if (config.password != null) password = config.password
            poolName = "d-migrate-${config.dialect.name.lowercase()}"
            maximumPoolSize = effectivePool.maximumPoolSize
            minimumIdle = effectivePool.minimumIdle
            connectionTimeout = effectivePool.connectionTimeoutMs
            idleTimeout = effectivePool.idleTimeoutMs
            maxLifetime = effectivePool.maxLifetimeMs
            keepaliveTime = effectivePool.keepaliveTimeMs
        }

        val dataSource = HikariDataSource(hikariConfig)
        return HikariConnectionPool(config.dialect, dataSource)
    }

    /**
     * Dialekt-spezifische Default-Parameter, die in die JDBC-URL injiziert
     * werden, sofern der User sie nicht bereits explizit gesetzt hat.
     *
     * - **SQLite** (siehe `connection-config-spec.md` §1.5):
     *   - `journal_mode=wal` — WAL-Modus für bessere Concurrency
     *   - `foreign_keys=true` — d-migrate verlässt sich auf referenzielle
     *     Integrität, in SQLite sind FKs sonst standardmäßig deaktiviert
     *
     * Phase B wird die `*JdbcUrlBuilder`-Klassen pro Treiber hinzufügen,
     * dann wandert dieses Mapping dorthin und MySQL/PostgreSQL bekommen
     * ihre eigenen Defaults (`useCursorFetch=true`, `application_name=d-migrate`).
     */
    private fun defaultsFor(dialect: DatabaseDialect): Map<String, String> = when (dialect) {
        DatabaseDialect.SQLITE -> mapOf(
            "journal_mode" to "wal",
            "foreign_keys" to "true",
        )
        DatabaseDialect.POSTGRESQL -> emptyMap()  // Phase B: application_name etc.
        DatabaseDialect.MYSQL -> emptyMap()       // Phase B: useCursorFetch etc.
    }

    /**
     * Baut eine minimale JDBC-URL aus der [ConnectionConfig]. Wird in Phase B
     * durch die dialekt-spezifischen `*JdbcUrlBuilder`-Klassen ersetzt.
     *
     * Die Query-Parameter werden korrekt URL-encoded zusammengesetzt — der
     * [ConnectionUrlParser] dekodiert sie beim Parsen, also müssen sie hier
     * wieder kodiert werden, damit Werte mit Sonderzeichen (Leerzeichen, `&`,
     * `=`, etc.) round-trip-sicher sind. Dialekt-Defaults aus [defaultsFor]
     * werden eingefügt, ohne explizit gesetzte User-Werte zu überschreiben.
     */
    private fun buildJdbcUrl(config: ConnectionConfig): String {
        // User-Params haben Vorrang vor Defaults — defaults zuerst, dann user.
        val mergedParams = LinkedHashMap<String, String>().apply {
            putAll(defaultsFor(config.dialect))
            putAll(config.params)
        }
        val paramString = if (mergedParams.isEmpty()) {
            ""
        } else {
            "?" + mergedParams.entries.joinToString("&") { (k, v) ->
                "${urlEncode(k)}=${urlEncode(v)}"
            }
        }
        return when (config.dialect) {
            DatabaseDialect.POSTGRESQL -> {
                val port = config.port ?: 5432
                "jdbc:postgresql://${config.host}:$port/${config.database}$paramString"
            }
            DatabaseDialect.MYSQL -> {
                val port = config.port ?: 3306
                "jdbc:mysql://${config.host}:$port/${config.database}$paramString"
            }
            DatabaseDialect.SQLITE -> {
                "jdbc:sqlite:${config.database}$paramString"
            }
        }
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8)
}

/** Hikari-basierte [ConnectionPool]-Implementierung. Internal: nicht direkt instanzieren. */
private class HikariConnectionPool(
    override val dialect: DatabaseDialect,
    private val dataSource: HikariDataSource,
) : ConnectionPool {

    override fun borrow(): Connection = dataSource.connection

    override fun activeConnections(): Int {
        val mxBean: HikariPoolMXBean? = dataSource.hikariPoolMXBean
        return mxBean?.activeConnections ?: 0
    }

    override fun close() {
        if (!dataSource.isClosed) {
            dataSource.close()
        }
    }
}
