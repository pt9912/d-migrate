package dev.dmigrate.server.persistence.jdbc.internal

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.sqlite.SQLiteDataSource
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

class JdbcTransactionRunnerTest : FunSpec({

    test("commits on success and returns block value") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ds = mockk<DataSource> { every { connection } returns conn }

        val result = JdbcTransactionRunner(ds).inTransaction { c ->
            c shouldBe conn
            "ok"
        }

        result shouldBe "ok"
        verifyOrder {
            ds.connection
            conn.autoCommit = false
            conn.commit()
            conn.close()
        }
        verify(exactly = 0) { conn.rollback() }
    }

    test("rolls back and rethrows when block throws") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ds = mockk<DataSource> { every { connection } returns conn }
        val boom = IllegalStateException("boom")

        val thrown = shouldThrow<IllegalStateException> {
            JdbcTransactionRunner(ds).inTransaction<Unit> { throw boom }
        }

        thrown shouldBe boom
        thrown.suppressed.size shouldBe 0
        verifyOrder {
            ds.connection
            conn.autoCommit = false
            conn.rollback()
            conn.close()
        }
        verify(exactly = 0) { conn.commit() }
    }

    test("primary throwable wins when rollback also throws; rollback added as suppressed") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ds = mockk<DataSource> { every { connection } returns conn }
        val rollbackError = SQLException("rollback failed")
        every { conn.rollback() } throws rollbackError
        val primary = RuntimeException("primary")

        val thrown = shouldThrow<RuntimeException> {
            JdbcTransactionRunner(ds).inTransaction<Unit> { throw primary }
        }

        thrown shouldBe primary
        thrown.suppressed.toList() shouldBe listOf(rollbackError)
        // Connection is still closed via use {}
        verify(exactly = 1) { conn.close() }
    }

    test("connection is closed even when commit itself throws") {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val ds = mockk<DataSource> { every { connection } returns conn }
        val commitError = SQLException("commit failed")
        every { conn.commit() } throws commitError
        every { conn.rollback() } just Runs

        val thrown = shouldThrow<SQLException> {
            JdbcTransactionRunner(ds).inTransaction { /* no-op */ }
        }

        thrown shouldBe commitError
        verifyOrder {
            conn.autoCommit = false
            conn.commit()
            conn.rollback()
            conn.close()
        }
    }

    test("borrows fresh connection per inTransaction call") {
        val conn1 = mockk<Connection>(relaxUnitFun = true)
        val conn2 = mockk<Connection>(relaxUnitFun = true)
        val ds = mockk<DataSource> { every { connection } returnsMany listOf(conn1, conn2) }
        val runner = JdbcTransactionRunner(ds)

        runner.inTransaction { it shouldBe conn1 }
        runner.inTransaction { it shouldBe conn2 }

        verify(exactly = 1) { conn1.commit() }
        verify(exactly = 1) { conn2.commit() }
        verify(exactly = 1) { conn1.close() }
        verify(exactly = 1) { conn2.close() }
    }

    test("real-DB durability: committed work is visible to subsequent transactions") {
        // SQLite file-mode for cross-connection visibility (in-memory ist per-connection).
        val tempFile = kotlin.io.path.createTempFile(prefix = "tx-runner-", suffix = ".db").toFile()
        tempFile.deleteOnExit()
        val ds = SQLiteDataSource().apply { url = "jdbc:sqlite:${tempFile.absolutePath}" }
        val runner = JdbcTransactionRunner(ds)

        ds.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE t (v TEXT NOT NULL)") }
        }

        runner.inTransaction { conn ->
            conn.prepareStatement("INSERT INTO t (v) VALUES (?)").use { ps ->
                ps.setString(1, "hello")
                ps.executeUpdate()
            }
        }

        val seen = ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT v FROM t").use { rs ->
                    rs.next() shouldBe true
                    rs.getString(1)
                }
            }
        }
        seen shouldBe "hello"
    }

    test("real-DB rollback: throwing block leaves no row behind") {
        val tempFile = kotlin.io.path.createTempFile(prefix = "tx-runner-rb-", suffix = ".db").toFile()
        tempFile.deleteOnExit()
        val ds = SQLiteDataSource().apply { url = "jdbc:sqlite:${tempFile.absolutePath}" }
        val runner = JdbcTransactionRunner(ds)

        ds.connection.use { conn ->
            conn.createStatement().use { it.execute("CREATE TABLE t (v TEXT NOT NULL)") }
        }

        shouldThrow<IllegalStateException> {
            runner.inTransaction { conn ->
                conn.prepareStatement("INSERT INTO t (v) VALUES ('orphan')").use { it.executeUpdate() }
                error("rollback please")
            }
        }

        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM t").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 0
                }
            }
        }
    }
})
