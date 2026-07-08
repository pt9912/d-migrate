package dev.dmigrate.driver.connection

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.sql.Connection
import java.sql.DriverManager

/**
 * P1-Tests für [JdbcDatabaseConnection] (ADR 0022). Wie [TimeoutDecoratedConnectionTest]
 * gegen eine echte SQLite-In-Memory-Connection — keine handgerollten Fakes.
 */
class JdbcDatabaseConnectionTest : FunSpec({

    fun openSqlite(): Connection {
        Class.forName("org.sqlite.JDBC")
        return DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    test("autoCommit spiegelt den Zustand der gewrappten Connection") {
        openSqlite().use { delegate ->
            val handle = JdbcDatabaseConnection(delegate)
            // Frische JDBC-Connection ist im Auto-Commit-Modus.
            handle.autoCommit shouldBe true
            delegate.autoCommit = false
            handle.autoCommit shouldBe false
        }
    }

    test("close delegiert an die gewrappte Connection") {
        val delegate = openSqlite()
        try {
            // Delegation ist hier physisch sichtbar (isClosed). Bei einer Hikari-Connection
            // ist genau diese Delegation die Pool-Rückgabe (close() schließt nicht physisch).
            JdbcDatabaseConnection(delegate).close()
            delegate.isClosed shouldBe true
        } finally {
            if (!delegate.isClosed) delegate.close()
        }
    }

    test("asJdbc liefert die gewrappte Connection zurück") {
        openSqlite().use { delegate ->
            val handle: DatabaseConnection = JdbcDatabaseConnection(delegate)
            handle.asJdbc() shouldBeSameInstanceAs delegate
        }
    }

    test("asJdbc auf einem Nicht-JDBC-Handle schlägt fehl") {
        val foreign = object : DatabaseConnection {
            override val autoCommit = true
            override fun close() {}
        }
        shouldThrow<IllegalStateException> { foreign.asJdbc() }
            .message!! shouldContain "keine JdbcDatabaseConnection"
    }
})
