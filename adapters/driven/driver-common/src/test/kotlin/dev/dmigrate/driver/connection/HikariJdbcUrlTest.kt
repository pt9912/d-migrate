package dev.dmigrate.driver.connection

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Verifiziert die JDBC-URL-Konstruktion in HikariConnectionPoolFactory pro
 * Dialekt — insbesondere die Default-Parameter, die beim Mergen mit User-
 * Parametern gesetzt werden (Plan §6.13 + Phase B.3).
 *
 * Diese Tests bauen die URL nur — sie öffnen keine echten Connections, weil
 * für PostgreSQL/MySQL kein Treiber im Test-Classpath ist (das wäre Phase B
 * Testcontainers-Scope).
 */
class HikariJdbcUrlTest : FunSpec({

    fun pgConfig(params: Map<String, String> = emptyMap()) = ConnectionConfig(
        dialect = DatabaseDialect.POSTGRESQL,
        host = "db.example.com",
        port = 5432,
        database = "mydb",
        user = "admin",
        password = "secret",
        params = params,
    )

    fun mysqlConfig(params: Map<String, String> = emptyMap()) = ConnectionConfig(
        dialect = DatabaseDialect.MYSQL,
        host = "mysql.example.com",
        port = 3306,
        database = "shop",
        user = "root",
        password = "rootpw",
        params = params,
    )

    fun sqliteConfig(params: Map<String, String> = emptyMap()) = ConnectionConfig(
        dialect = DatabaseDialect.SQLITE,
        host = null,
        port = null,
        database = "/tmp/test.db",
        user = null,
        password = null,
        params = params,
    )

    // ─── PostgreSQL defaults ────────────────────────────────────

    test("PostgreSQL JDBC URL injects ApplicationName=d-migrate by default") {
        val url = HikariConnectionPoolFactory.buildJdbcUrl(pgConfig())
        url shouldContain "jdbc:postgresql://db.example.com:5432/mydb"
        url shouldContain "ApplicationName=d-migrate"
    }

    test("user can override PostgreSQL ApplicationName") {
        val url = HikariConnectionPoolFactory.buildJdbcUrl(
            pgConfig(mapOf("ApplicationName" to "my-app"))
        )
        url shouldContain "ApplicationName=my-app"
        url shouldNotContain "ApplicationName=d-migrate"
    }

    test("PostgreSQL URL uses default port 5432 when not provided") {
        val cfg = pgConfig().copy(port = null)
        HikariConnectionPoolFactory.buildJdbcUrl(cfg) shouldContain ":5432/"
    }

    // ─── MySQL defaults (F8 from plan §3.3 / §6.13) ─────────────

    test("MySQL JDBC URL injects useCursorFetch=true by default") {
        val url = HikariConnectionPoolFactory.buildJdbcUrl(mysqlConfig())
        url shouldContain "jdbc:mysql://mysql.example.com:3306/shop"
        url shouldContain "useCursorFetch=true"
    }

    test("MySQL JDBC URL does not inject allowPublicKeyRetrieval by default") {
        val url = HikariConnectionPoolFactory.buildJdbcUrl(mysqlConfig())
        url shouldNotContain "allowPublicKeyRetrieval"
    }

    test("user can opt in to MySQL allowPublicKeyRetrieval explicitly") {
        val url = HikariConnectionPoolFactory.buildJdbcUrl(
            mysqlConfig(mapOf("allowPublicKeyRetrieval" to "true"))
        )
        url shouldContain "allowPublicKeyRetrieval=true"
    }

    test("user can override MySQL useCursorFetch") {
        val url = HikariConnectionPoolFactory.buildJdbcUrl(
            mysqlConfig(mapOf("useCursorFetch" to "false"))
        )
        url shouldContain "useCursorFetch=false"
        url shouldNotContain "useCursorFetch=true"
    }

    test("MySQL URL uses default port 3306 when not provided") {
        val cfg = mysqlConfig().copy(port = null)
        HikariConnectionPoolFactory.buildJdbcUrl(cfg) shouldContain ":3306/"
    }

    // ─── SQLite defaults (already covered by HikariConnectionPoolFactoryTest, double check here) ───

    test("SQLite JDBC URL injects journal_mode=wal and foreign_keys=true") {
        val url = HikariConnectionPoolFactory.buildJdbcUrl(sqliteConfig())
        url shouldContain "jdbc:sqlite:/tmp/test.db"
        url shouldContain "journal_mode=wal"
        url shouldContain "foreign_keys=true"
    }

    test("SQLite :memory: with params uses file::memory: URI form, not a literal filename") {
        val cfg = sqliteConfig(
            params = linkedMapOf(
                "cache" to "shared",
                "app name" to "d migrate",
            ),
        ).copy(database = ":memory:")

        val url = HikariConnectionPoolFactory.buildJdbcUrl(cfg)

        url shouldContain "jdbc:sqlite:file::memory:"
        url shouldContain "cache=shared"
        url shouldContain "app+name=d+migrate"
        url shouldNotContain "jdbc:sqlite::memory:?"
    }

    // ─── User params with special chars are re-encoded ──────────

    test("query parameter values with special chars are URL-encoded") {
        val url = HikariConnectionPoolFactory.buildJdbcUrl(
            pgConfig(mapOf("custom" to "value with spaces", "x" to "a&b"))
        )
        // %20 für Leerzeichen, %26 für &
        url shouldContain "custom=value+with+spaces" // URLEncoder uses + for spaces
        url shouldContain "x=a%26b"
    }
})
