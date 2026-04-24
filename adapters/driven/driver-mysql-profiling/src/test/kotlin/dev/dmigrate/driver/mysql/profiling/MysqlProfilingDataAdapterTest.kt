package dev.dmigrate.driver.mysql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.profiling.types.TargetLogicalType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection

class MysqlProfilingDataAdapterTest : FunSpec({

    val conn = mockk<Connection>()
    val pool = mockk<ConnectionPool> {
        every { dialect } returns DatabaseDialect.MYSQL
        every { borrow() } returns conn
    }
    every { conn.close() } returns Unit

    val jdbc = mockk<JdbcOperations>()
    val adapter = MysqlProfilingDataAdapter(jdbcFactory = { jdbc })

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
        val m = adapter.columnMetrics(pool, "users", "name", "varchar(100)")
        m.nonNullCount shouldBe 4
        m.nullCount shouldBe 1
        m.emptyStringCount shouldBe 0
    }

    test("topValues returns value frequencies") {
        every { jdbc.querySingle(match { it.contains("count(*)") }) } returns mapOf("cnt" to 10L)
        every { jdbc.queryList(match { it.contains("GROUP BY") }, any()) } returns listOf(
            mapOf("val" to "Alice", "cnt" to 4L),
        )
        val top = adapter.topValues(pool, "users", "name", 5)
        top shouldHaveSize 1
        top[0].count shouldBe 4
    }

    test("numericStats returns stats") {
        every {
            jdbc.querySingle(match { it.contains("min") && it.contains("max") && !it.contains("min_ts") })
        } returns mapOf(
            "min_val" to 1.0, "max_val" to 100.0, "avg_val" to 50.0,
            "sum_val" to 500.0, "stddev_val" to 28.0,
            "zero_count" to 0L, "neg_count" to 0L,
        )
        val stats = adapter.numericStats(pool, "users", "score")
        stats shouldNotBe null
        stats!!.min shouldBe 1.0
    }

    test("temporalStats returns min/max") {
        every { jdbc.querySingle(match { it.contains("min_ts") }) } returns mapOf(
            "min_ts" to "2026-01-01", "max_ts" to "2026-04-17",
        )
        val stats = adapter.temporalStats(pool, "users", "created_at")
        stats shouldNotBe null
        stats!!.minTimestamp shouldBe "2026-01-01"
    }

    test("targetTypeCompatibility returns results") {
        every { jdbc.querySingle(match { it.contains("checked") }) } returns mapOf(
            "checked" to 5L, "compat" to 5L, "incompat" to 0L,
        )
        val compat = adapter.targetTypeCompatibility(pool, "users", "name", listOf(TargetLogicalType.STRING))
        compat shouldHaveSize 1
        compat[0].compatibleCount shouldBe 5
    }

    // ── security: malicious identifiers ────────────

    test("rowCount quotes malicious table name to prevent injection") {
        clearMocks(jdbc)
        every { jdbc.querySingle(any()) } returns mapOf("cnt" to 1L)
        adapter.rowCount(pool, "Robert'; DROP TABLE users; --")
        verify {
            jdbc.querySingle(match {
                it.contains("`Robert'; DROP TABLE users; --`")
            })
        }
    }

    test("rowCount schema-qualifies MySQL table path when schema is provided") {
        clearMocks(jdbc)
        every { jdbc.querySingle(any()) } returns mapOf("cnt" to 1L)

        adapter.rowCount(pool, "select`; DROP", schema = "tenant.one")

        verify {
            jdbc.querySingle(match {
                it.contains("FROM `tenant.one`.`select``; DROP`")
            })
        }
    }

    test("columnMetrics quotes malicious column name with backtick escape") {
        clearMocks(jdbc)
        every { jdbc.querySingle(any()) } returns mapOf(
            "non_null_count" to 1L, "null_count" to 0L,
            "distinct_count" to 1L, "dup_count" to 0L,
            "min_val" to "x", "max_val" to "x",
        )
        adapter.columnMetrics(pool, "t", "col`inject", "int")
        verify {
            jdbc.querySingle(match {
                it.contains("`col``inject`")
            })
        }
    }

    test("profiling SQL quotes edge-case identifiers consistently") {
        clearMocks(jdbc)
        val cases = listOf(
            "has`quote" to "`has``quote`",
            "has.dot" to "`has.dot`",
            "semi;colon" to "`semi;colon`",
            "select" to "`select`",
            "unicodе" to "`unicodе`",
            "" to "``",
        )

        cases.forEach { (identifier, expected) ->
            every { jdbc.querySingle(any()) } returns mapOf("cnt" to 1L)
            adapter.rowCount(pool, identifier)
            verify {
                jdbc.querySingle(match { it.contains("FROM $expected") })
            }
        }
    }
})
