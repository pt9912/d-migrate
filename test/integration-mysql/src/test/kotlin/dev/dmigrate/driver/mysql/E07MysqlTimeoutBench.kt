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
 * MySQL setzt `MAX_EXECUTION_TIME` per `connectionInitSql`, das nur für
 * SELECTs greift; Write-/DDL-Pfade werden zusätzlich durch das per-
 * Statement `setQueryTimeout(...)` aus `TimeoutDecoratedConnection`
 * (E0.7.3 Common-JDBC-Layer) abgedeckt. Dieser Bench testet den
 * SELECT-Pfad und verifiziert Cleanup.
 */
class E07MysqlTimeoutBench : FunSpec({

    tags(IntegrationTag)

    val container = MySQLContainer("mysql:8.0")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

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

    test("MAX_EXECUTION_TIME enforces <= 5s on long-running SELECT SLEEP") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 5_000, netMs = 5_000)).use { pool ->
            val start = System.nanoTime()
            shouldThrow<SQLException> {
                pool.borrow().use { conn ->
                    conn.prepareStatement("SELECT SLEEP(60)").executeQuery()
                }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            elapsedMs shouldBeLessThan 6_000L
            // MySQL throws either MySQLTimeoutException (subclass of
            // SQLTimeoutException) for MAX_EXECUTION_TIME, or a generic
            // SQLException if the Statement-level setQueryTimeout fires
            // first. Both flavors satisfy the budget; we don't pin the
            // SQLState because driver versions differ.
        }
    }

    test("Cleanup: pool returns connection after timeout, healthy SELECT works") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 5_000, netMs = 5_000)).use { pool ->
            shouldThrow<SQLException> {
                pool.borrow().use { conn ->
                    conn.prepareStatement("SELECT SLEEP(60)").executeQuery()
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
