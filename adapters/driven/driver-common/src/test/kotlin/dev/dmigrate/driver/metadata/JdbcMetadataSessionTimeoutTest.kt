package dev.dmigrate.driver.metadata

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.driver.connection.HikariConnectionPoolFactory
import dev.dmigrate.driver.connection.PoolSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement

/**
 * Phase E0.7.4 regression guard: [JdbcMetadataSession] (used by every
 * profiling adapter, every schema reader and every metadata-heavy code
 * path) creates its statements via the borrowed [Connection]. When that
 * connection comes from [HikariConnectionPoolFactory] the
 * [dev.dmigrate.driver.connection.TimeoutDecoratedConnection] applies
 * `setQueryTimeout(...)` automatically — without the session having to
 * be aware. This test belegt the chain end-to-end without integration
 * tags by wrapping the borrowed connection with a capturing layer.
 */
class JdbcMetadataSessionTimeoutTest : FunSpec({

    fun cfg(stmtMs: Int) = ConnectionConfig(
        dialect = DatabaseDialect.SQLITE,
        host = null,
        port = null,
        database = ":memory:",
        user = null,
        password = null,
        pool = PoolSettings(statementTimeoutMs = stmtMs),
    )

    /** Records every Statement / PreparedStatement that flows through
     *  the wrapped connection so callers can verify queryTimeout. */
    class CapturingConnection(private val delegate: Connection) : Connection by delegate {
        val statements: MutableList<Statement> = mutableListOf()
        override fun createStatement(): Statement =
            delegate.createStatement().also { statements += it }
        override fun prepareStatement(sql: String): PreparedStatement =
            delegate.prepareStatement(sql).also { statements += it }
    }

    test("queryList without params runs through the timeout-decorated createStatement") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 7_000)).use { pool ->
            pool.borrow().use { decorated ->
                val capturing = CapturingConnection(decorated)
                val session = JdbcMetadataSession(capturing)
                session.queryList("SELECT 1 AS x") // implicit Map result
                capturing.statements.shouldNotBeEmpty()
                capturing.statements.all { it.queryTimeout == 7 } shouldBe true
            }
        }
    }

    test("queryList with params runs through the timeout-decorated prepareStatement") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 4_000)).use { pool ->
            pool.borrow().use { decorated ->
                val capturing = CapturingConnection(decorated)
                val session = JdbcMetadataSession(capturing)
                session.queryList("SELECT ? AS x", 42)
                capturing.statements.shouldNotBeEmpty()
                capturing.statements.all { it.queryTimeout == 4 } shouldBe true
            }
        }
    }

    test("querySingle without params runs through the decorator") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 9_000)).use { pool ->
            pool.borrow().use { decorated ->
                val capturing = CapturingConnection(decorated)
                JdbcMetadataSession(capturing).querySingle("SELECT 1 AS x")
                capturing.statements.all { it.queryTimeout == 9 } shouldBe true
            }
        }
    }

    test("execute(sql) runs through the decorator") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 11_000)).use { pool ->
            pool.borrow().use { decorated ->
                decorated.createStatement().use { it.execute("CREATE TABLE t (id INTEGER)") }
                val capturing = CapturingConnection(decorated)
                JdbcMetadataSession(capturing).execute("INSERT INTO t (id) VALUES (1)")
                capturing.statements.shouldNotBeEmpty()
                capturing.statements.all { it.queryTimeout == 11 } shouldBe true
            }
        }
    }

    test("executeBatch runs through the decorator") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 13_000)).use { pool ->
            pool.borrow().use { decorated ->
                decorated.createStatement().use { it.execute("CREATE TABLE t (id INTEGER)") }
                val capturing = CapturingConnection(decorated)
                JdbcMetadataSession(capturing).executeBatch(
                    "INSERT INTO t (id) VALUES (?)",
                    listOf(arrayOf<Any?>(1), arrayOf<Any?>(2)),
                )
                capturing.statements.shouldNotBeEmpty()
                capturing.statements.all { it.queryTimeout == 13 } shouldBe true
            }
        }
    }

    test("statementTimeoutMs == 0 leaves all metadata-session statements with no timeout") {
        HikariConnectionPoolFactory.create(cfg(stmtMs = 0)).use { pool ->
            pool.borrow().use { decorated ->
                val capturing = CapturingConnection(decorated)
                JdbcMetadataSession(capturing).queryList("SELECT 1")
                capturing.statements.all { it.queryTimeout == 0 } shouldBe true
            }
        }
    }
})
