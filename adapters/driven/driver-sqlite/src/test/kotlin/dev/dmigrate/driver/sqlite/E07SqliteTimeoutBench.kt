package dev.dmigrate.driver.sqlite

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.PoolSettings
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.sql.SQLException

private val IntegrationTag = NamedTag("integration")

/**
 * Phase E0.7.4 Bench-Test: belegt empirisch, dass das Cancel-Reaktions-
 * Budget für SQLite-Queries via xerial-jdbc respektiert wird.
 *
 * xerial-sqlite-jdbc 3.51+ implementiert `Statement.setQueryTimeout(s)`
 * über einen Watchdog-Thread, der `sqlite3_interrupt(...)` aufruft.
 * Dieser Test verwendet eine CPU-schwere Recursive-CTE, die mehrere
 * Sekunden bräuchte, und verifiziert, dass die Query nach
 * `<= statementTimeoutMs + slack` mit einer SQLException endet.
 */
class E07SqliteTimeoutBench : FunSpec({

    tags(IntegrationTag)

    fun memCfg(stmtMs: Int = 30_000, netMs: Int = 30_000) = ConnectionConfig(
        dialect = DatabaseDialect.SQLITE,
        host = null,
        port = null,
        database = ":memory:",
        user = null,
        password = null,
        pool = PoolSettings(statementTimeoutMs = stmtMs, networkTimeoutMs = netMs),
    )

    /** A CPU-bound query that runs for many seconds without timeout —
     *  100M recursive CTE iterations, aggregated via `MAX(n)` so SQLite
     *  streams the recursion (no full materialization, low memory). */
    val longCte = """
        WITH RECURSIVE long_loop(n) AS (
            SELECT 1
            UNION ALL
            SELECT n + 1 FROM long_loop WHERE n < 100000000
        )
        SELECT MAX(n) FROM long_loop
    """.trimIndent()

    test("setQueryTimeout enforces <= 2s on long recursive CTE") {
        HikariConnectionPoolFactory.create(memCfg(stmtMs = 2_000, netMs = 2_000)).use { pool ->
            val start = System.nanoTime()
            shouldThrow<SQLException> {
                pool.borrow().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(longCte).use { rs -> rs.next() }
                    }
                }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            elapsedMs shouldBeLessThan 4_000L  // 2s timeout + 2s slack
        }
    }

    test("Cleanup: pool returns connection after timeout, healthy SELECT works") {
        HikariConnectionPoolFactory.create(memCfg(stmtMs = 2_000, netMs = 2_000)).use { pool ->
            shouldThrow<SQLException> {
                pool.borrow().use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeQuery(longCte).use { rs -> rs.next() }
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
        HikariConnectionPoolFactory.create(memCfg()).use { pool ->
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
