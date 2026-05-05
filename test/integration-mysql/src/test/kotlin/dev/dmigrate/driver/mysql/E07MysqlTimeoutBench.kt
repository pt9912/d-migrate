package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.DatabaseDriverRegistry
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.PoolSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.testcontainers.mysql.MySQLContainer
import java.sql.SQLException

private val IntegrationTag = NamedTag("integration")

/**
 * Phase E0.7.4 Bench-Test: belegt empirisch das Cancel-Reaktions-Budget
 * für langlaufende MySQL-SELECT-Queries.
 *
 * Wichtige MySQL-Spezifika:
 * - `MAX_EXECUTION_TIME` (per `connectionInitSql` aus E0.7.2) greift nur
 *   für read-only SELECTs, **nicht** für SELECTs mit Built-in-Funktionen
 *   wie `SLEEP()` oder `BENCHMARK()`. Wir können diese als Long-Query
 *   nicht verwenden.
 * - `Statement.setQueryTimeout(s)` (per E0.7.3 `TimeoutDecoratedConnection`)
 *   wird via TimerTask + `KILL QUERY` umgesetzt und greift auf jeder
 *   Query-Art, sofern der Connection-User entsprechende Privilegien hat.
 *
 * Die Bench nutzt deshalb einen 2-Wege-Cross-Join über
 * `information_schema.columns`, der MySQL zur echten Row-Materialisierung
 * zwingt — sowohl `MAX_EXECUTION_TIME` als auch `setQueryTimeout` feuern
 * hier zuverlässig.
 */
class E07MysqlTimeoutBench : FunSpec({

    tags(IntegrationTag)

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    /** 2-way cross join — produces millions of intermediate rows that MySQL
     *  must enumerate before COUNT can return. Reliably > 5s on CI. */
    val longSelect = """
        SELECT COUNT(*)
        FROM information_schema.columns t1,
             information_schema.columns t2
    """.trimIndent()

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(MysqlDriver())
    }

    afterSpec {
        container.stop()
    }

    fun cfg(stmtMs: Int = 30_000, netMs: Int = 30_000) = ConnectionConfig(
        dialect = DatabaseDialect.MYSQL,
        host = container.host,
        port = container.firstMappedPort,
        database = container.databaseName,
        user = container.username,
        password = container.password,
        pool = PoolSettings(statementTimeoutMs = stmtMs, networkTimeoutMs = netMs),
    )

    test("statementTimeoutMs enforces <= 5s on a long read-only cross join") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 5_000, netMs = 5_000)).use { pool ->
            pool.borrow().use { conn ->
                // Decorator wiring sanity: the prepared statement carries
                // ceil(5000/1000) = 5 seconds before we even execute.
                conn.prepareStatement("SELECT 1").use { stmt -> stmt.queryTimeout shouldBe 5 }

                val start = System.nanoTime()
                shouldThrow<SQLException> {
                    conn.prepareStatement(longSelect).use { stmt ->
                        stmt.executeQuery().use { rs -> while (rs.next()) { /* drain */ } }
                    }
                }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                // 5s timeout + generous CI slack (Hikari acquire +
                // KILL QUERY round-trip latency).
                elapsedMs shouldBeLessThan 10_000L
            }
        }
    }

    test("Cleanup: pool returns connection after timeout, healthy SELECT works") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 5_000, netMs = 5_000)).use { pool ->
            shouldThrow<SQLException> {
                pool.borrow().use { conn ->
                    conn.prepareStatement(longSelect).use { stmt ->
                        stmt.executeQuery().use { rs -> while (rs.next()) { /* drain */ } }
                    }
                }
            }
            pool.activeConnections() shouldBeLessThanOrEqual 1

            pool.borrow().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT 1").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        }
    }

    test("Default timeout (30000) does not break a fast SELECT 1") {
        HikariConnectionPoolFactory.create(cfg()).use { pool ->
            pool.borrow().use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT 1").use { rs ->
                        rs.next() shouldBe true
                        rs.getInt(1) shouldBe 1
                    }
                }
            }
        }
    }
})
