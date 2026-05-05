package dev.dmigrate.driver.connection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import org.slf4j.LoggerFactory
import java.sql.Connection

/**
 * Erzeugt einen [ConnectionPool] (HikariCP-basiert) aus einer [ConnectionConfig].
 *
 * Die JDBC-URL wird über den [JdbcUrlBuilder] des registrierten [DatabaseDriver][dev.dmigrate.driver.DatabaseDriver]
 * gebaut (via [DatabaseDriverRegistry]). Wenn kein Driver registriert ist —
 * typischerweise in Unit-Tests ohne konkreten Treiber — wird der
 * [FallbackJdbcUrlBuilder] verwendet.
 *
 * Dialekt-spezifische Pool-Anpassungen:
 * - **SQLite**: `maximumPoolSize = 1` (SQLite erlaubt keine parallelen
 *   Schreibzugriffe; siehe `connection-config-spec.md` §2.2)
 */
object HikariConnectionPoolFactory {

    private val log = LoggerFactory.getLogger(HikariConnectionPoolFactory::class.java)

    /** Erzeugt einen offenen [ConnectionPool]. Caller MUSS `pool.close()` aufrufen. */
    fun create(config: ConnectionConfig): ConnectionPool {
        val effectivePool = if (config.dialect == DatabaseDialect.SQLITE) {
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
            connectionInitSqlFor(config.dialect, effectivePool.statementTimeoutMs)?.let {
                connectionInitSql = it
            }
        }

        val dataSource = HikariDataSource(hikariConfig)
        return HikariConnectionPool(config.dialect, dataSource)
    }

    /**
     * Erzeugt das driver-spezifische `connectionInitSql` aus dem
     * Cancel-Reaktions-Budget aus implementation-plan-0.9.6 §4.1.
     *
     * Pro Dialekt:
     * - PostgreSQL: `SET statement_timeout = $ms` — wirkt auf alle
     *   Statements (SELECT/INSERT/UPDATE/DDL) der jeweiligen Connection.
     * - MySQL: `SET SESSION MAX_EXECUTION_TIME = $ms` — wirkt **nur auf
     *   SELECTs** (MySQL-Quirk). Write-Pfade benötigen zusätzlich den
     *   gemeinsamen JDBC-Timeout-Layer aus E0.7.3.
     * - SQLite: `PRAGMA busy_timeout = $ms` — Lock-Wait-Timeout. Lange
     *   Range-Scans benötigen zusätzlich `setQueryTimeout` aus dem
     *   gemeinsamen Layer.
     *
     * Wert `0` deaktiviert den Init-SQL-Pfad (Treiber-Default greift,
     * üblicherweise unbegrenzt). Negative Werte sind durch
     * [PoolSettings.init] bereits ausgeschlossen.
     *
     * `internal` für Tests.
     */
    internal fun connectionInitSqlFor(dialect: DatabaseDialect, statementTimeoutMs: Int): String? {
        if (statementTimeoutMs <= 0) return null
        return when (dialect) {
            DatabaseDialect.POSTGRESQL -> "SET statement_timeout = $statementTimeoutMs"
            DatabaseDialect.MYSQL -> "SET SESSION MAX_EXECUTION_TIME = $statementTimeoutMs"
            DatabaseDialect.SQLITE -> "PRAGMA busy_timeout = $statementTimeoutMs"
        }
    }

    /**
     * Baut die JDBC-URL über den [JdbcUrlBuilder] des registrierten Drivers
     * oder den [FallbackJdbcUrlBuilder]. `internal` für Tests.
     */
    internal fun buildJdbcUrl(config: ConnectionConfig): String {
        val builder = try {
            DatabaseDriverRegistry.get(config.dialect).urlBuilder()
        } catch (e: IllegalArgumentException) {
            log.debug("No registered driver for {}, using fallback URL builder: {}", config.dialect, e.message)
            FallbackJdbcUrlBuilder(config.dialect)
        }
        return builder.buildJdbcUrl(config)
    }
}

/**
 * Fallback-Builder mit den gleichen Default-Parametern wie die produktiven
 * Treiber-Builder. Wird verwendet, wenn kein Driver in der
 * [DatabaseDriverRegistry] registriert ist (z.B. in driver-common Unit-Tests).
 *
 * **Wichtig**: Diese Klasse darf nicht aus dem `driver-api`-Modul herauslecken
 * und sollte nicht von Tests in den Treiber-Modulen verwendet werden — dort
 * kommt der echte registrierte Builder zum Einsatz.
 */
internal class FallbackJdbcUrlBuilder(override val dialect: DatabaseDialect) : JdbcUrlBuilder {
    override fun defaultParams(): Map<String, String> = when (dialect) {
        DatabaseDialect.SQLITE -> mapOf(
            "journal_mode" to "wal",
            "foreign_keys" to "true",
        )
        DatabaseDialect.POSTGRESQL -> mapOf(
            "ApplicationName" to "d-migrate",
        )
        DatabaseDialect.MYSQL -> mapOf(
            "useCursorFetch" to "true",
            "rewriteBatchedStatements" to "true",
        )
    }

    override fun baseJdbcUrl(config: ConnectionConfig): String = when (config.dialect) {
        DatabaseDialect.POSTGRESQL -> {
            val port = config.port ?: 5432
            "jdbc:postgresql://${config.host}:$port/${config.database}"
        }
        DatabaseDialect.MYSQL -> {
            val port = config.port ?: 3306
            "jdbc:mysql://${config.host}:$port/${config.database}"
        }
        DatabaseDialect.SQLITE -> "jdbc:sqlite:${config.database}"
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
