package dev.dmigrate.driver.connection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import dev.dmigrate.driver.DatabaseDialect
import java.sql.Connection

/**
 * Erzeugt einen [ConnectionPool] (HikariCP-basiert) aus einer [ConnectionConfig].
 *
 * Die JDBC-URL wird über einen registrierten [JdbcUrlBuilder] gebaut (siehe
 * [JdbcUrlBuilderRegistry]). Wenn keiner registriert ist — typischerweise in
 * Unit-Tests im `driver-api`-Modul ohne konkreten Treiber — wird der
 * [FallbackJdbcUrlBuilder] verwendet, der die gleichen Defaults injiziert wie
 * die produktiven Builder, damit Tests nicht vom Bootstrap-Zustand abhängen.
 *
 * Dialekt-spezifische Pool-Anpassungen:
 * - **SQLite**: `maximumPoolSize = 1` (SQLite erlaubt keine parallelen
 *   Schreibzugriffe; siehe `connection-config-spec.md` §2.2)
 */
object HikariConnectionPoolFactory {

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
        }

        val dataSource = HikariDataSource(hikariConfig)
        return HikariConnectionPool(config.dialect, dataSource)
    }

    /**
     * Baut die JDBC-URL über einen registrierten [JdbcUrlBuilder] oder den
     * [FallbackJdbcUrlBuilder]. `internal` für Tests.
     */
    internal fun buildJdbcUrl(config: ConnectionConfig): String {
        val builder = JdbcUrlBuilderRegistry.find(config.dialect) ?: FallbackJdbcUrlBuilder(config.dialect)
        return builder.buildJdbcUrl(config)
    }
}

/**
 * Fallback-Builder mit den gleichen Default-Parametern wie die produktiven
 * Treiber-Builder. Wird verwendet, wenn kein Builder über die
 * [JdbcUrlBuilderRegistry] registriert ist (z.B. in driver-api Unit-Tests).
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
            "useUnicode" to "true",
            // Java-Charset-Name (siehe MysqlJdbcUrlBuilder).
            "characterEncoding" to "UTF-8",
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
