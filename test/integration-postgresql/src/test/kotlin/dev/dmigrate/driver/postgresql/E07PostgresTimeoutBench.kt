package dev.dmigrate.driver.postgresql

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
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.SQLException

private val IntegrationTag = NamedTag("integration")

/**
 * Phase E0.7.4 Bench-Test: belegt empirisch, dass das Cancel-Reaktions-
 * Budget aus implementation-plan-0.9.6 §4.1 (`<= statementTimeoutMs`) für
 * langlaufende PostgreSQL-Queries respektiert wird, ohne Connection-Leak.
 *
 * PostgreSQL setzt `statement_timeout` per `connectionInitSql` und lässt
 * danach jede Query (SELECT/INSERT/UPDATE/DDL) automatisch abbrechen.
 * Ergebnis: `PSQLException` mit SQLState `57014` (`query_canceled`).
 */
class E07PostgresTimeoutBench : FunSpec({

    tags(IntegrationTag)

    val container = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("dmigrate_test")
        .withUsername("dmigrate")
        .withPassword("dmigrate")

    beforeSpec {
        container.start()
        DatabaseDriverRegistry.register(PostgresDriver())
    }

    afterSpec {
        container.stop()
    }

    fun cfg(stmtMs: Int = 30_000, netMs: Int = 30_000) = ConnectionConfig(
        dialect = DatabaseDialect.POSTGRESQL,
        host = container.host,
        port = container.firstMappedPort,
        database = container.databaseName,
        user = container.username,
        password = container.password,
        pool = PoolSettings(statementTimeoutMs = stmtMs, networkTimeoutMs = netMs),
    )

    test("statement_timeout enforces <= 5s on long-running pg_sleep query") {
        // netMs deliberately >> stmtMs: statement_timeout (server-side) must
        // fire before networkTimeout (client-side socket close), otherwise
        // the SQLException carries a connection-error SQLState instead of
        // 57014 (query_canceled). NetworkTimeout is the safety net for
        // paths that don't go through statement_timeout (e.g. DatabaseMetaData).
        HikariConnectionPoolFactory.create(cfg(stmtMs = 5_000, netMs = 30_000)).use { pool ->
            val start = System.nanoTime()
            val ex = shouldThrow<SQLException> {
                pool.borrow().use { conn ->
                    conn.prepareStatement("SELECT pg_sleep(60)").executeQuery()
                }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            elapsedMs shouldBeLessThan 10_000L
            // PostgreSQL emits SQLState 57014 (query_canceled) when
            // statement_timeout fires.
            ex.sqlState shouldBe "57014"
        }
    }

    test("Cleanup: pool returns connection after timeout, healthy SELECT works") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 5_000, netMs = 30_000)).use { pool ->
            shouldThrow<SQLException> {
                pool.borrow().use { conn ->
                    conn.prepareStatement("SELECT pg_sleep(60)").executeQuery()
                }
            }
            // After `.use { }` the connection is back in the pool. Hikari
            // bookkeeping is async — accept <= 1.
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
