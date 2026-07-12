package dev.dmigrate.driver.sqlite.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.DatabaseConnection
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.connection.asJdbc
import dev.dmigrate.profiling.types.TargetLogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.sql.Connection
import java.sql.DriverManager

/**
 * Regression: `data profile` crasht bei Integer-/Zahlenspalten und bei leeren
 * Tabellen (v0.9.10, m-trace-Pilot).
 *
 * - Bug 1: `targetTypeCompatibility` castet inkompatible Beispielwerte ungeprüft
 *   auf String → `Integer cannot be cast to String` bei jeder Integer-Spalte.
 * - Bug 2: `sum(case …)` über 0 Zeilen liefert NULL → `null as Number`-Crash in
 *   `columnMetrics` (empty/blank_count) und `numericStats` (zero/neg_count).
 */
class SqliteProfilingEmptyAndNonStringTest : FunSpec({

    val pool = object : ConnectionPool {
        private val realConn = DriverManager.getConnection("jdbc:sqlite::memory:")
        override val dialect = DatabaseDialect.SQLITE
        override fun borrow(): DatabaseConnection = JdbcDatabaseConnection(object : Connection by realConn {
            override fun close() { /* keep in-memory DB alive */ }
        })
        override fun activeConnections() = 0
        override fun close() = realConn.close()
    }
    val data = SqliteProfilingDataAdapter()

    beforeSpec {
        pool.borrow().asJdbc().createStatement().use { stmt ->
            stmt.execute("CREATE TABLE t_int (id INTEGER PRIMARY KEY AUTOINCREMENT, label TEXT NOT NULL)")
            stmt.execute("INSERT INTO t_int(label) VALUES ('x'),('y')")
            stmt.execute("CREATE TABLE t_int_plain (k TEXT PRIMARY KEY, n INTEGER NOT NULL)")
            stmt.execute("INSERT INTO t_int_plain VALUES ('a',1),('b',2)")
            stmt.execute("CREATE TABLE t_empty (id TEXT PRIMARY KEY, val TEXT)")
            stmt.execute("CREATE TABLE t_empty_int (k TEXT PRIMARY KEY, n INTEGER)")
        }
    }

    test("Bug 1: targetTypeCompatibility auf Integer-Spalte crasht nicht, Beispiele als String") {
        val result = data.targetTypeCompatibility(pool, "t_int", "id", listOf(TargetLogicalType.BOOLEAN), null)
        result shouldHaveSize 1
        // id=2 ist BOOLEAN-inkompatibel → als Beispiel-String, kein ClassCastException.
        result[0].incompatibleCount shouldBe 1L
        result[0].exampleInvalidValues shouldBe listOf("2")
    }

    test("Bug 1: plain-INTEGER-Spalte gegen mehrere Zieltypen crasht nicht") {
        val result = data.targetTypeCompatibility(
            pool, "t_int_plain", "n",
            listOf(TargetLogicalType.INTEGER, TargetLogicalType.BOOLEAN, TargetLogicalType.DATE), null,
        )
        result shouldHaveSize 3
    }

    test("Bug 2: columnMetrics auf leerer TEXT-Spalte crasht nicht (empty/blank = 0)") {
        val metrics = data.columnMetrics(pool, "t_empty", "id", "TEXT", null)
        metrics.nonNullCount shouldBe 0L
        metrics.emptyStringCount shouldBe 0L
        metrics.blankStringCount shouldBe 0L
    }

    test("Bug 2: targetTypeCompatibility auf leerer Tabelle crasht nicht (compat/incompat = 0)") {
        val result = data.targetTypeCompatibility(
            pool, "t_empty", "id",
            listOf(TargetLogicalType.INTEGER, TargetLogicalType.BOOLEAN), null,
        )
        result shouldHaveSize 2
        result.all { it.compatibleCount == 0L && it.incompatibleCount == 0L } shouldBe true
    }

    test("Bug 2: numericStats auf leerer INTEGER-Spalte crasht nicht (zero/neg = 0)") {
        val stats = data.numericStats(pool, "t_empty_int", "n", null)
        stats shouldNotBe null
        stats!!.zeroCount shouldBe 0L
        stats.negativeCount shouldBe 0L
        stats.min shouldBe null
    }
})
