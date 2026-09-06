package dev.dmigrate.driver.connection

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.zaxxer.hikari.HikariPoolMXBean
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.SQLFeatureNotSupportedException
import java.util.concurrent.Executor
import kotlin.math.ceil

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

        // VA4: opt-in SpatiaLite. Das d-migrate-eigene `?spatialite=true` wird in
        // xerials `enable_load_extension=true` übersetzt (das proprietäre Flag selbst
        // kennt der Treiber nicht und muss aus der URL); `mod_spatialite` wird per
        // connectionInitSql geladen. Der busy_timeout, sonst per connectionInitSql,
        // wandert für solche Connections als xerial-Pragma in die URL.
        val spatialite = isSpatialiteRequested(config)
        val effectiveConfig = if (spatialite) {
            val extra = linkedMapOf("enable_load_extension" to "true")
            if (effectivePool.statementTimeoutMs > 0) {
                extra["busy_timeout"] = effectivePool.statementTimeoutMs.toString()
            }
            config.copy(params = (config.params - SPATIALITE_PARAM) + extra)
        } else {
            config
        }

        val hikariConfig = HikariConfig().apply {
            jdbcUrl = buildJdbcUrl(effectiveConfig)
            if (config.user != null) username = config.user
            if (config.password != null) password = config.password
            poolName = "d-migrate-${config.dialect.name.lowercase()}"
            maximumPoolSize = effectivePool.maximumPoolSize
            minimumIdle = effectivePool.minimumIdle
            connectionTimeout = effectivePool.connectionTimeoutMs
            idleTimeout = effectivePool.idleTimeoutMs
            maxLifetime = effectivePool.maxLifetimeMs
            keepaliveTime = effectivePool.keepaliveTimeMs
            // ojdbc verlangt fuer JSON-Spalten eine ausdrueckliche Ansage, als
            // WAS `getObject()` sie liefern soll -- ohne sie scheitert JEDER
            // Lesezugriff auf eine solche Spalte mit `ORA-18722`, nicht erst
            // die Umwandlung. Das trifft d-migrate an mehreren Stellen (Oracle
            // als Transfer-Quelle, `data export`, der `--verify`-Rueckleseweg),
            // weil `NeutralType.Array` und `NeutralType.Json` beide auf `JSON`
            // abbilden. `String` ist die Form, in der der neutrale Datenpfad
            // den Wert ohnehin fuehrt.
            if (config.dialect == DatabaseDialect.ORACLE) {
                addDataSourceProperty("oracle.jdbc.jsonDefaultGetObjectType", "java.lang.String")
            }
            if (spatialite) {
                // busy_timeout steckt für SpatiaLite-Connections in der URL (s.o.).
                connectionInitSql = "SELECT load_extension('mod_spatialite')"
            } else {
                connectionInitSqlFor(config.dialect, effectivePool.statementTimeoutMs)?.let {
                    connectionInitSql = it
                }
            }
        }

        val dataSource = HikariDataSource(hikariConfig)
        return HikariConnectionPool(
            dialect = config.dialect,
            dataSource = dataSource,
            statementTimeoutSeconds = timeoutSecondsOf(effectivePool.statementTimeoutMs),
            networkTimeoutMs = effectivePool.networkTimeoutMs,
        )
    }

    /**
     * Konvertiert ein Millisekunden-Budget in das von
     * [java.sql.Statement.setQueryTimeout] verlangte Sekunden-Format und
     * rundet sub-Sekunden-Werte AUF (LN-010 — verhindert, dass `500ms`
     * versehentlich zu `0` und damit zu "disabled" wird).
     *
     * `<=0` bleibt `0` (expliziter Disable-Pfad).
     *
     * `internal` für Tests.
     */
    internal fun timeoutSecondsOf(timeoutMs: Int): Int {
        if (timeoutMs <= 0) return 0
        return ceil(timeoutMs / 1000.0).toInt()
    }

    /**
     * LN-010: Erzeugt das driver-spezifische `connectionInitSql` aus dem
     * Cancel-Reaktions-Budget.
     *
     * Pro Dialekt:
     * - PostgreSQL: `SET statement_timeout = $ms` — wirkt auf alle
     *   Statements (SELECT/INSERT/UPDATE/DDL) der jeweiligen Connection.
     * - MySQL: `SET SESSION MAX_EXECUTION_TIME = $ms` — wirkt **nur auf
     *   SELECTs** (MySQL-Quirk). Write-Pfade benötigen zusätzlich den
     *   gemeinsamen JDBC-Timeout-Layer aus LF-012 / LN-011 / LN-017 / LN-027.
     * - SQLite: `PRAGMA busy_timeout = $ms` — Lock-Wait-Timeout. Lange
     *   Range-Scans benötigen zusätzlich `setQueryTimeout` aus dem
     *   gemeinsamen Layer.
     * - MSSQL: `SET LOCK_TIMEOUT $ms` — Lock-Wait-Timeout; T-SQL hat kein
     *   Session-Statement-Budget, das deckt der gemeinsame
     *   `setQueryTimeout`-Layer ab.
     *
     * Wert `0` deaktiviert den Init-SQL-Pfad (Treiber-Default greift,
     * üblicherweise unbegrenzt). Negative Werte sind durch
     * [PoolSettings.init] bereits ausgeschlossen.
     *
     * `internal` für Tests.
     */
    /**
     * VA4: ob für diese (SQLite-)Connection SpatiaLite via `?spatialite=true`
     * angefordert wurde (truthy: true/1/on/yes, case-insensitiv). Nur SQLite;
     * andere Dialekte ignorieren das Flag. `internal` für Tests.
     */
    internal fun isSpatialiteRequested(config: ConnectionConfig): Boolean =
        config.dialect == DatabaseDialect.SQLITE &&
            config.params[SPATIALITE_PARAM]?.lowercase() in setOf("true", "1", "on", "yes")

    internal const val SPATIALITE_PARAM = "spatialite"

    internal fun connectionInitSqlFor(dialect: DatabaseDialect, statementTimeoutMs: Int): String? {
        if (statementTimeoutMs <= 0) return null
        return when (dialect) {
            DatabaseDialect.POSTGRESQL -> "SET statement_timeout = $statementTimeoutMs"
            DatabaseDialect.MYSQL -> "SET SESSION MAX_EXECUTION_TIME = $statementTimeoutMs"
            DatabaseDialect.SQLITE -> "PRAGMA busy_timeout = $statementTimeoutMs"
            DatabaseDialect.MSSQL -> "SET LOCK_TIMEOUT $statementTimeoutMs"
            // Oracle hat kein einfaches Session-Statement-Budget per SQL-
            // Init-Statement (das naechste Aequivalent waere Resource-Manager-
            // Konfiguration, DBA-seitig) -- der gemeinsame setQueryTimeout-
            // Layer traegt hier allein, analog zu MySQLs Write-Pfad-Luecke.
            DatabaseDialect.ORACLE -> null
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
        DatabaseDialect.MSSQL -> mapOf(
            "applicationName" to "d-migrate",
        )
        // Kein Oracle-spezifischer Default-Tuning-Parameter identifiziert
        // (Slice 1) -- leer statt eines ungeprueften ojdbc-Property-Namens.
        DatabaseDialect.ORACLE -> emptyMap()
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
        DatabaseDialect.MSSQL ->
            SqlServerJdbcUrl.base(config.host, config.port, config.database)
        DatabaseDialect.ORACLE -> {
            // ojdbc "thin"-Treiber, EZConnect-Form; config.database ist der
            // Service-Name (nicht die SID) -- Standard-Listener-Port 1521.
            val port = config.port ?: 1521
            "jdbc:oracle:thin:@//${config.host}:$port/${config.database}"
        }
    }

    override fun buildJdbcUrl(config: ConnectionConfig): String =
        if (config.dialect == DatabaseDialect.MSSQL) {
            // mssql-jdbc nutzt `;key=value`-Properties statt `?k=v&`; die
            // Assemblierung inkl. SSL-Mapping teilt sich der Fallback mit dem
            // produktiven MssqlJdbcUrlBuilder (Review-Befund: divergente Kopie
            // liess encrypt/trustServerCertificate im Fallback fallen).
            SqlServerJdbcUrl.assemble(config, defaultParams())
        } else {
            super.buildJdbcUrl(config)
        }
}

/**
 * Hikari-basierte [ConnectionPool]-Implementierung. Internal: nicht direkt
 * instanzieren.
 *
 * Wraps every borrowed [Connection] in a [TimeoutDecoratedConnection]
 * (LN-010 Common-JDBC-Timeout-Layer) and applies
 * [Connection.setNetworkTimeout] for connection-level I/O bounds, sodass
 * insbesondere `DatabaseMetaData`-Direkt-Calls wie `getPrimaryKeys` aus
 * PostgreSQL/MySQL-Writer-Open nicht ungebunden bleiben.
 *
 * Drivers, die [Connection.setNetworkTimeout] nicht implementieren
 * (`SQLFeatureNotSupportedException`), fallen still auf den Statement-
 * Timeout-Pfad zurück; Statement-Level-Timeouts greifen weiterhin via
 * Decorator.
 */
private class HikariConnectionPool(
    override val dialect: DatabaseDialect,
    private val dataSource: HikariDataSource,
    private val statementTimeoutSeconds: Int,
    private val networkTimeoutMs: Int,
) : ConnectionPool {

    override fun borrow(): DatabaseConnection {
        val raw = dataSource.connection
        if (networkTimeoutMs > 0) {
            try {
                raw.setNetworkTimeout(DirectExecutor, networkTimeoutMs)
            } catch (_: SQLFeatureNotSupportedException) {
                // Fall back silently: Statement-level timeouts via the
                // TimeoutDecoratedConnection still bound query duration.
                // SQLite and other drivers may not support network timeout;
                // statement-level timeouts still bound query duration.
            }
        }
        return JdbcDatabaseConnection(TimeoutDecoratedConnection(raw, statementTimeoutSeconds))
    }

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

/**
 * Caller-thread executor for [Connection.setNetworkTimeout]. JDBC uses
 * the executor for cleanup-side runnables when a connection is forcibly
 * timed out; running on the caller thread is acceptable for d-migrate's
 * single-job-per-pool usage and avoids Guava as a transitive dep.
 */
private object DirectExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}
