package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.data.DataWriter
import dev.dmigrate.driver.data.ImportOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * LN-013: Clean-Load-Rollback-Helfer — truncatet den vollen Tabellensatz,
 * best-effort + geloggt.
 */
class AtomicCompensationTest : FunSpec({

    val pool = object : ConnectionPool {
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): DatabaseConnection = throw UnsupportedOperationException()
        override fun activeConnections() = 0
        override fun close() {}
    }

    fun writer(onTruncate: (List<String>) -> Unit) = object : DataWriter {
        override val dialect = DatabaseDialect.SQLITE
        override fun schemaSync() = throw UnsupportedOperationException()
        override fun openTable(pool: ConnectionPool, table: String, options: ImportOptions) =
            throw UnsupportedOperationException()

        override fun truncateTables(pool: ConnectionPool, tables: List<String>) = onTruncate(tables)
    }

    test("rollback truncates all tables and logs the revert") {
        val truncated = mutableListOf<List<String>>()
        val log = mutableListOf<String>()

        AtomicCompensation.rollback(writer { truncated += it }, pool, listOf("a", "b"), log::add)

        truncated shouldBe listOf(listOf("a", "b"))
        log.single() shouldContain "reverted 2 table(s)"
    }

    test("rollback logs a clear failure message when truncate throws (best-effort)") {
        val log = mutableListOf<String>()

        AtomicCompensation.rollback(
            writer { throw RuntimeException("disk full") },
            pool,
            listOf("a"),
            log::add,
        )

        log.single() shouldContain "atomic rollback FAILED"
        log.single() shouldContain "disk full"
    }
})
