package dev.dmigrate.driver.connection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import dev.dmigrate.driver.DatabaseDialect
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
     * Baut eine minimale JDBC-URL aus der [ConnectionConfig]. Wird in Phase B
     * durch die dialekt-spezifischen `*JdbcUrlBuilder`-Klassen ersetzt, die
     * dann auch dialekt-spezifische Defaults wie `useCursorFetch=true`
     * (MySQL) oder `application_name=d-migrate` (PostgreSQL) injizieren.
     */
    private fun buildJdbcUrl(config: ConnectionConfig): String {
        val params = if (config.params.isEmpty()) {
            ""
        } else {
            "?" + config.params.entries.joinToString("&") { (k, v) -> "$k=$v" }
        }
        return when (config.dialect) {
            DatabaseDialect.POSTGRESQL -> {
                val port = config.port ?: 5432
                "jdbc:postgresql://${config.host}:$port/${config.database}$params"
            }
            DatabaseDialect.MYSQL -> {
                val port = config.port ?: 3306
                "jdbc:mysql://${config.host}:$port/${config.database}$params"
            }
            DatabaseDialect.SQLITE -> {
                "jdbc:sqlite:${config.database}$params"
            }
        }
    }
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
