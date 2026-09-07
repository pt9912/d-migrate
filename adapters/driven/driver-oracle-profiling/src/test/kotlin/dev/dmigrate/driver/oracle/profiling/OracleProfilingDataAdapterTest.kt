package dev.dmigrate.driver.oracle.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.profiling.model.DeterminationStatus
import dev.dmigrate.profiling.types.TargetLogicalType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.sql.Connection

class OracleProfilingDataAdapterTest : FunSpec({

    val conn = mockk<Connection>()
    val pool = mockk<ConnectionPool> {
        every { dialect } returns DatabaseDialect.ORACLE
        every { borrow() } returns JdbcDatabaseConnection(conn)
    }
    every { conn.close() } returns Unit

    val jdbc = mockk<JdbcOperations>()
    val adapter = OracleProfilingDataAdapter(jdbcFactory = { jdbc })

    fun stubColumnType(type: String) {
        every { jdbc.querySingle(match { it.contains("FROM all_tab_columns") }, any(), any(), any()) } returns
            mapOf("data_type" to type)
    }

    test("rowCount reads the count") {
        every { jdbc.querySingle(match { it.contains("COUNT(*)") }) } returns mapOf("cnt" to 5L)
        adapter.rowCount(pool, "users") shouldBe 5L
    }

    test("an empty string is never counted: in Oracle it IS null") {
        // Die Werte stehen in nullCount. Sie hier als etwas anderes zu melden
        // waere eine Aussage ueber einen Server, der das nicht unterscheidet.
        val sql = slot<String>()
        every { jdbc.querySingle(capture(sql)) } returns mapOf(
            "non_null_count" to 3L, "null_count" to 1L, "distinct_count" to 2L, "dup_count" to 1L,
            "min_val" to "a", "max_val" to "b", "blank_count" to 1L, "min_len" to 1, "max_len" to 2,
        )
        val metrics = adapter.columnMetrics(pool, "t", "nm", "VARCHAR2")
        metrics.emptyStringCount shouldBe 0
        metrics.nullCount shouldBe 1L
        metrics.blankStringCount shouldBe 1L
        metrics.minLength shouldBe 1
        // Leerraum erkennt man daran, dass der Wert nicht NULL ist, sein
        // getrimmter Wert aber schon.
        sql.captured shouldContain "TRIM"
    }

    test("a numeric column carries no length or blank metrics") {
        every { jdbc.querySingle(any()) } returns mapOf(
            "non_null_count" to 4L, "null_count" to 0L, "distinct_count" to 4L, "dup_count" to 0L,
            "min_val" to "1", "max_val" to "9",
        )
        val metrics = adapter.columnMetrics(pool, "t", "amt", "NUMBER")
        metrics.minLength.shouldBeNull()
        metrics.maxLength.shouldBeNull()
        metrics.blankStringCount shouldBe 0
    }

    test("a CLOB column is counted through the truncated projection") {
        val sql = slot<String>()
        every { jdbc.querySingle(capture(sql)) } returns mapOf(
            "non_null_count" to 2L, "null_count" to 0L, "distinct_count" to 2L, "dup_count" to 0L,
            "min_val" to "a", "max_val" to "b", "blank_count" to 0L, "min_len" to 5, "max_len" to 5,
        )
        adapter.columnMetrics(pool, "t", "body", "CLOB")
        // Ohne die Projektion scheiterte COUNT(DISTINCT) mit ORA-22849.
        sql.captured shouldContain "SUBSTR"
        sql.captured shouldContain "DBMS_LOB.GETLENGTH"
    }

    test("duplicates never go negative") {
        every { jdbc.querySingle(any()) } returns mapOf(
            "non_null_count" to 2L, "null_count" to 0L, "distinct_count" to 5L, "dup_count" to 0L,
            "min_val" to null, "max_val" to null,
        )
        adapter.columnMetrics(pool, "t", "amt", "NUMBER").duplicateValueCount shouldBe 0L
    }

    test("topValues counts on the same borrowed connection and limits server-side") {
        // Eigener Pool-Mock: der geteilte zaehlt die Aufrufe der uebrigen
        // Tests mit.
        val ownPool = mockk<ConnectionPool> {
            every { dialect } returns DatabaseDialect.ORACLE
            every { borrow() } returns JdbcDatabaseConnection(conn)
        }
        val sql = slot<String>()
        every { jdbc.querySingle(match { it.contains("COUNT(*) AS cnt") }) } returns mapOf("cnt" to 10L)
        stubColumnType("VARCHAR2")
        every { jdbc.queryList(capture(sql), any()) } returns listOf(
            mapOf("val" to "a", "cnt" to 6L),
            mapOf("val" to "b", "cnt" to 4L),
        )
        val values = adapter.topValues(ownPool, "t", "nm", limit = 2)
        values shouldHaveSize 2
        values[0].value shouldBe "a"
        values[0].ratio shouldBe 0.6
        // Oracle kennt kein LIMIT.
        sql.captured shouldContain "FETCH FIRST"
        // Genau EINE geborgte Verbindung: ein zweites rowCount(pool, …)
        // erschoepfte einen Pool der Groesse 1.
        verify(exactly = 1) { ownPool.borrow() }
    }

    test("topValues on an empty table returns nothing instead of dividing by zero") {
        every { jdbc.querySingle(match { it.contains("COUNT(*) AS cnt") }) } returns mapOf("cnt" to 0L)
        adapter.topValues(pool, "t", "nm").shouldBeEmpty()
    }

    test("numericStats uses the population standard deviation") {
        val sql = slot<String>()
        every { jdbc.querySingle(capture(sql)) } returns mapOf(
            "min_val" to 1, "max_val" to 9, "avg_val" to 5.0, "sum_val" to 10,
            "stddev_val" to 2.5, "zero_count" to 1L, "neg_count" to 0L,
        )
        val stats = adapter.numericStats(pool, "t", "amt")!!
        stats.stddev shouldBe 2.5
        stats.zeroCount shouldBe 1L
        sql.captured shouldContain "STDDEV_POP"
    }

    test("temporalStats picks the mask from the column type and reports ISO text") {
        val sql = slot<String>()
        stubColumnType("TIMESTAMP(6)")
        every { jdbc.querySingle(capture(sql)) } returns
            mapOf("min_ts" to "2024-01-01T00:00:00.000000000", "max_ts" to "2024-12-31T23:59:59.000000000")
        val stats = adapter.temporalStats(pool, "t", "ts")!!
        stats.minTimestamp shouldBe "2024-01-01T00:00:00.000000000"
        sql.captured shouldContain "YYYY-MM-DD"
        // Ein TIMESTAMP traegt Bruchteilsekunden; ohne sie faenden zwei
        // verschiedene Zeitpunkte denselben Text.
        sql.captured shouldContain ".FF9"
    }

    test("temporalStats returns nothing for a non-temporal column instead of guessing a mask") {
        stubColumnType("NUMBER")
        adapter.temporalStats(pool, "t", "amt").shouldBeNull()
    }

    test("a column whose type cannot be aggregated is named, not left to the server") {
        // COUNT(long) waere ORA-00997 -- der Betreiber saehe nur den Code.
        val failure = shouldThrow<UnsupportedOperationException> {
            adapter.columnMetrics(pool, "t", "lg", "LONG")
        }
        failure.message!! shouldContain "cannot be profiled"
        failure.message!! shouldContain "ORA-00997"
    }

    test("compatibility is a full scan via VALIDATE_CONVERSION, with examples only when something fails") {
        stubColumnType("VARCHAR2")
        every { jdbc.querySingle(match { it.contains("checked") }) } returns
            mapOf("checked" to 10L, "compat" to 8L, "incompat" to 2L)
        every { jdbc.queryList(match { it.contains("DISTINCT") }) } returns
            listOf(mapOf("val" to "x"), mapOf("val" to "y"))
        val result = adapter.targetTypeCompatibility(pool, "t", "nm", listOf(TargetLogicalType.INTEGER)).single()
        result.determinationStatus shouldBe DeterminationStatus.FULL_SCAN
        result.compatibleCount shouldBe 8L
        result.exampleInvalidValues shouldBe listOf("x", "y")
    }

    test("no examples are fetched when every value fits") {
        stubColumnType("VARCHAR2")
        every { jdbc.querySingle(match { it.contains("checked") }) } returns
            mapOf("checked" to 10L, "compat" to 10L, "incompat" to 0L)
        adapter.targetTypeCompatibility(pool, "t", "nm", listOf(TargetLogicalType.STRING))
            .single().exampleInvalidValues.shouldBeEmpty()
    }
})
