package dev.dmigrate.driver.postgresql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.profiling.types.TargetLogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection

class PostgresProfilingDataAdapterTest : FunSpec({

    val conn = mockk<Connection>()
    val pool = mockk<ConnectionPool> {
        every { dialect } returns DatabaseDialect.POSTGRESQL
        every { borrow() } returns conn
    }
    // Connection.close() is called by pool.borrow().use {}
    every { conn.close() } returns Unit

    val jdbc = mockk<JdbcOperations>()
    val adapter = PostgresProfilingDataAdapter(jdbcFactory = { jdbc })

    test("rowCount returns count from query") {
        every { jdbc.querySingle(match { it.contains("count(*)") }) } returns mapOf("cnt" to 5L)
        adapter.rowCount(pool, "users") shouldBe 5L
    }

    test("columnMetrics for text column") {
        every { jdbc.querySingle(match { it.contains("non_null_count") }) } returns mapOf(
            "non_null_count" to 4L, "null_count" to 1L,
            "distinct_count" to 3L, "dup_count" to 1L,
            "min_val" to "alice", "max_val" to "charlie",
            "empty_count" to 0L, "blank_count" to 0L,
            "min_len" to 5, "max_len" to 7,
        )
        val m = adapter.columnMetrics(pool, "users", "name", "text")
        m.nonNullCount shouldBe 4
        m.nullCount shouldBe 1
        m.distinctCount shouldBe 3
        m.emptyStringCount shouldBe 0
        m.minLength shouldBe 5
        m.maxLength shouldBe 7
    }

    test("columnMetrics for non-text column omits text fields") {
        every { jdbc.querySingle(match { it.contains("non_null_count") }) } returns mapOf(
            "non_null_count" to 5L, "null_count" to 0L,
            "distinct_count" to 5L, "dup_count" to 0L,
            "min_val" to "1", "max_val" to "5",
        )
        val m = adapter.columnMetrics(pool, "users", "id", "integer")
        m.nonNullCount shouldBe 5
        m.emptyStringCount shouldBe 0
        m.minLength shouldBe null
    }

    test("topValues returns empty list for empty table") {
        every { jdbc.querySingle(match { it.contains("count(*)") }) } returns mapOf("cnt" to 0L)
        adapter.topValues(pool, "users", "name", 10) shouldHaveSize 0
    }

    test("topValues returns value frequencies") {
        every { jdbc.querySingle(match { it.contains("count(*)") }) } returns mapOf("cnt" to 10L)
        every { jdbc.queryList(match { it.contains("GROUP BY") }, any()) } returns listOf(
            mapOf("val" to "Alice", "cnt" to 4L),
            mapOf("val" to "Bob", "cnt" to 3L),
        )
        val top = adapter.topValues(pool, "users", "name", 5)
        top shouldHaveSize 2
        top[0].value shouldBe "Alice"
        top[0].count shouldBe 4
    }

    test("numericStats returns stats") {
        every { jdbc.querySingle(match { it.contains("min") && it.contains("numeric") }) } returns mapOf(
            "min_val" to 1.0, "max_val" to 100.0, "avg_val" to 50.5,
            "sum_val" to 505.0, "stddev_val" to 28.8,
            "zero_count" to 0L, "neg_count" to 0L,
        )
        val stats = adapter.numericStats(pool, "users", "score")
        stats shouldNotBe null
        stats!!.min shouldBe 1.0
        stats.max shouldBe 100.0
    }

    test("numericStats returns null for empty result") {
        every { jdbc.querySingle(match { it.contains("numeric") }) } returns null
        adapter.numericStats(pool, "users", "score") shouldBe null
    }

    test("temporalStats returns min/max timestamps") {
        every { jdbc.querySingle(match { it.contains("min_ts") }) } returns mapOf(
            "min_ts" to "2026-01-01", "max_ts" to "2026-04-17",
        )
        val stats = adapter.temporalStats(pool, "users", "created_at")
        stats shouldNotBe null
        stats!!.minTimestamp shouldBe "2026-01-01"
    }

    test("targetTypeCompatibility returns compatibility results") {
        every { jdbc.querySingle(match { it.contains("checked") }) } returns mapOf(
            "checked" to 10L, "compat" to 10L, "incompat" to 0L,
        )
        val compat = adapter.targetTypeCompatibility(pool, "users", "name", listOf(TargetLogicalType.STRING))
        compat shouldHaveSize 1
        compat[0].compatibleCount shouldBe 10
        compat[0].incompatibleCount shouldBe 0
    }

    test("targetTypeCompatibility with incompatible values fetches examples") {
        every { jdbc.querySingle(match { it.contains("checked") }) } returns mapOf(
            "checked" to 5L, "compat" to 2L, "incompat" to 3L,
        )
        every { jdbc.queryList(match { it.contains("DISTINCT") }) } returns listOf(
            mapOf("val" to "abc"), mapOf("val" to "def"),
        )
        val compat = adapter.targetTypeCompatibility(pool, "users", "age", listOf(TargetLogicalType.INTEGER))
        compat[0].incompatibleCount shouldBe 3
        compat[0].exampleInvalidValues shouldHaveSize 2
    }

    // ── security: malicious identifiers ────────────

    test("rowCount quotes malicious table name to prevent injection") {
        every { jdbc.querySingle(any()) } returns mapOf("cnt" to 1L)
        adapter.rowCount(pool, "Robert'; DROP TABLE users; --")
        verify {
            jdbc.querySingle(match {
                it.contains("\"Robert'; DROP TABLE users; --\"")
            })
        }
    }

    test("columnMetrics quotes malicious column name") {
        every { jdbc.querySingle(any()) } returns mapOf(
            "non_null_count" to 1L, "null_count" to 0L,
            "distinct_count" to 1L, "dup_count" to 0L,
            "min_val" to "x", "max_val" to "x",
            "empty_count" to 0L, "blank_count" to 0L,
            "min_len" to 1, "max_len" to 1,
        )
        adapter.columnMetrics(pool, "t", "col\"with\"quotes", "text")
        verify {
            jdbc.querySingle(match {
                it.contains("\"col\"\"with\"\"quotes\"")
            })
        }
    }
})
