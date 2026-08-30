package dev.dmigrate.driver.mssql.profiling

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.profiling.model.DeterminationStatus
import dev.dmigrate.profiling.types.TargetLogicalType
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.sql.Connection

class MssqlProfilingDataAdapterTest : FunSpec({

    val conn = mockk<Connection>()
    val pool = mockk<ConnectionPool> {
        every { dialect } returns DatabaseDialect.MSSQL
        every { borrow() } returns JdbcDatabaseConnection(conn)
    }
    every { conn.close() } returns Unit

    val jdbc = mockk<JdbcOperations>()
    val adapter = MssqlProfilingDataAdapter(jdbcFactory = { jdbc })

    test("rowCount reads the count") {
        every { jdbc.querySingle(match { it.contains("count(*)") }) } returns mapOf("cnt" to 5L)
        adapter.rowCount(pool, "users") shouldBe 5L
    }

    test("a text column carries length and blank metrics, a numeric one does not") {
        every { jdbc.querySingle(match { it.contains("non_null_count") }) } returns mapOf(
            "non_null_count" to 4L, "null_count" to 1L, "distinct_count" to 3L, "dup_count" to 1L,
            "min_val" to "alice", "max_val" to "charlie",
            "empty_count" to 1L, "blank_count" to 2L, "min_len" to 5, "max_len" to 7,
        )
        val text = adapter.columnMetrics(pool, "users", "name", "nvarchar(100)")
        text.nonNullCount shouldBe 4
        text.emptyStringCount shouldBe 1
        text.blankStringCount shouldBe 2
        text.minLength shouldBe 5
        text.maxLength shouldBe 7
        text.minValue shouldBe "alice"

        val numeric = adapter.columnMetrics(pool, "users", "score", "int")
        numeric.emptyStringCount shouldBe 0
        numeric.minLength.shouldBeNull()
    }

    // `sum(case …)` ueber null Zeilen liefert NULL, nicht 0.
    test("an empty table yields zeros, not nulls") {
        every { jdbc.querySingle(match { it.contains("non_null_count") }) } returns mapOf(
            "non_null_count" to 0L, "null_count" to 0L, "distinct_count" to 0L, "dup_count" to 0L,
            "min_val" to null, "max_val" to null,
            "empty_count" to null, "blank_count" to null, "min_len" to null, "max_len" to null,
        )
        val m = adapter.columnMetrics(pool, "users", "name", "varchar(50)")
        m.emptyStringCount shouldBe 0
        m.blankStringCount shouldBe 0
        m.minLength.shouldBeNull()
    }

    test("topValues asks for TOP (?) and computes the share") {
        val sql = slot<String>()
        every { jdbc.querySingle(match { it.contains("count(*)") }) } returns mapOf("cnt" to 10L)
        every { jdbc.querySingle(match { it.contains("type_name") }, *anyVararg()) } returns
            mapOf("type_name" to "nvarchar")
        every { jdbc.queryList(capture(sql), any()) } returns listOf(mapOf("val" to "Alice", "cnt" to 4L))
        val top = adapter.topValues(pool, "users", "name", 5)
        top shouldHaveSize 1
        top[0].count shouldBe 4
        top[0].ratio shouldBe 0.4
        // T-SQL kennt kein LIMIT.
        sql.captured shouldBe sql.captured.also { it.contains("TOP (?)") shouldBe true }
    }

    test("topValues on an empty table returns nothing instead of dividing by zero") {
        every { jdbc.querySingle(match { it.contains("count(*)") }) } returns mapOf("cnt" to 0L)
        every { jdbc.querySingle(match { it.contains("type_name") }, *anyVararg()) } returns
            mapOf("type_name" to "nvarchar")
        adapter.topValues(pool, "users", "name", 5).shouldBeEmpty()
    }

    // Live gegen SQL Server gemessen: `COUNT(DISTINCT)` weist diese sechs
    // Typen ab, `GROUP BY` und `ORDER BY` ebenso.
    test("types T-SQL cannot compare are counted over their projection") {
        val sql = slot<String>()
        every { jdbc.querySingle(capture(sql)) } returns mapOf(
            "non_null_count" to 1L, "null_count" to 0L, "distinct_count" to 1L, "dup_count" to 0L,
            "min_val" to "POINT (1 2)", "max_val" to "POINT (1 2)",
        )
        listOf("geometry", "geography", "xml", "text", "ntext").forEach { type ->
            adapter.columnMetrics(pool, "t", "c", type)
            withClue(type) {
                sql.captured.contains("count(distinct CAST([c] AS NVARCHAR(4000)))") shouldBe true
                // Auch das blanke COUNT weist diese Typen ab.
                sql.captured.contains("count(CAST([c] AS NVARCHAR(4000))) AS non_null_count") shouldBe true
            }
        }
        // `image` laesst sich nicht nach nvarchar wandeln — nur ueber varbinary.
        adapter.columnMetrics(pool, "t", "c", "image")
        sql.captured.contains("CONVERT(VARBINARY(MAX), [c])") shouldBe true

        // Ein vergleichbarer Typ zaehlt weiter auf der Spalte selbst; die
        // Projektion auf 4000 Zeichen faltete sonst lange Werte zusammen.
        adapter.columnMetrics(pool, "t", "c", "nvarchar")
        sql.captured.contains("count(distinct [c])") shouldBe true
    }

    // `LEN`, `TRIM` und der Vergleich mit '' weisen text/ntext ab.
    test("legacy LOB text columns measure length over the projection") {
        val sql = slot<String>()
        every { jdbc.querySingle(capture(sql)) } returns mapOf(
            "non_null_count" to 1L, "null_count" to 0L, "distinct_count" to 1L, "dup_count" to 0L,
            "min_val" to "x", "max_val" to "x",
            "empty_count" to 0L, "blank_count" to 0L, "min_len" to 1, "max_len" to 1,
        )
        adapter.columnMetrics(pool, "t", "c", "text")
        sql.captured.contains("LEN(CAST([c] AS NVARCHAR(4000)))") shouldBe true

        // `'   ' = ''` ist in T-SQL wahr — der Leer-Test laeuft ueber DATALENGTH.
        sql.captured.contains("DATALENGTH(CAST([c] AS NVARCHAR(4000))) = 0") shouldBe true

        adapter.columnMetrics(pool, "t", "c", "nvarchar")
        sql.captured.contains("LEN([c])") shouldBe true
    }

    test("binary values are rendered as hex, not as control characters") {
        val sql = slot<String>()
        every { jdbc.querySingle(capture(sql)) } returns mapOf(
            "non_null_count" to 1L, "null_count" to 0L, "distinct_count" to 1L, "dup_count" to 0L,
            "min_val" to "0x01", "max_val" to "0x01",
        )
        adapter.columnMetrics(pool, "t", "c", "varbinary")
        sql.captured.contains("CONVERT(NVARCHAR(4000), CONVERT(VARBINARY(MAX), [c]), 1)") shouldBe true
    }

    test("numericStats casts to FLOAT and uses STDEVP") {
        val sql = slot<String>()
        every { jdbc.querySingle(capture(sql)) } returns mapOf(
            "min_val" to 1.0, "max_val" to 100.0, "avg_val" to 50.0, "sum_val" to 500.0,
            "stddev_val" to 28.0, "zero_count" to 1L, "neg_count" to 2L,
        )
        val stats = adapter.numericStats(pool, "users", "score").shouldNotBeNull()
        stats.min shouldBe 1.0
        stats.zeroCount shouldBe 1
        stats.negativeCount shouldBe 2
        sql.captured.contains("STDEVP") shouldBe true
    }

    test("numericStats and temporalStats return null when the query yields no row") {
        every { jdbc.querySingle(any()) } returns null
        adapter.numericStats(pool, "users", "score").shouldBeNull()
        adapter.temporalStats(pool, "users", "created_at").shouldBeNull()
    }

    // Stil 126 ist ISO 8601 — dieselbe Textform wie beim PostgreSQL-Profiler.
    test("temporalStats converts with ISO style 126") {
        val sql = slot<String>()
        every { jdbc.querySingle(capture(sql)) } returns mapOf(
            "min_ts" to "2024-01-01T00:00:00", "max_ts" to "2024-06-01T00:00:00",
        )
        val stats = adapter.temporalStats(pool, "users", "created_at").shouldNotBeNull()
        stats.minTimestamp shouldBe "2024-01-01T00:00:00"
        sql.captured.contains(", 126)") shouldBe true
    }

    // T-SQL hat keine Regex; `TRY_CONVERT` beantwortet die Frage direkt.
    test("target-type compatibility uses TRY_CONVERT and collects examples only when something fails") {
        val sql = slot<String>()
        every { jdbc.querySingle(match { it.contains("type_name") }, *anyVararg()) } returns
            mapOf("type_name" to "nvarchar")
        every { jdbc.querySingle(capture(sql)) } returns mapOf(
            "checked" to 10L, "compat" to 8L, "incompat" to 2L,
        )
        every { jdbc.queryList(match { it.contains("DISTINCT TOP (3)") }) } returns listOf(
            mapOf("val" to "abc"), mapOf("val" to "xyz"),
        )
        val result = adapter.targetTypeCompatibility(pool, "users", "code", listOf(TargetLogicalType.INTEGER))
        result shouldHaveSize 1
        result[0].incompatibleCount shouldBe 2
        result[0].exampleInvalidValues shouldBe listOf("abc", "xyz")
        result[0].determinationStatus shouldBe DeterminationStatus.FULL_SCAN
        sql.captured.contains("TRY_CONVERT(BIGINT") shouldBe true
        // `TRY_CONVERT(BIGINT, '')` liefert 0, nicht NULL.
        sql.captured.contains("LEN(TRIM(") shouldBe true
    }

    // Ohne Stil richtet sich die Wandlung nach der Sprache der Anmeldung:
    // `13/02/2024` ist unter `british` ein Datum, unter `us_english` keins.
    test("date compatibility pins ISO style 126 so the profile does not depend on the login language") {
        val sql = slot<String>()
        every { jdbc.querySingle(match { it.contains("type_name") }, *anyVararg()) } returns
            mapOf("type_name" to "nvarchar")
        every { jdbc.querySingle(capture(sql)) } returns mapOf("checked" to 1L, "compat" to 1L, "incompat" to 0L)

        adapter.targetTypeCompatibility(pool, "t", "c", listOf(TargetLogicalType.DATE))
        sql.captured.contains("TRY_CONVERT(DATE, CAST([c] AS NVARCHAR(4000)), 126)") shouldBe true

        adapter.targetTypeCompatibility(pool, "t", "c", listOf(TargetLogicalType.DATETIME))
        sql.captured.contains("TRY_CONVERT(DATETIME2, CAST([c] AS NVARCHAR(4000)), 126)") shouldBe true
    }

    // Sonst zaehlte topValues anders als der `distinctCount` derselben Spalte.
    test("topValues groups over the same expression columnMetrics counts") {
        val sql = slot<String>()
        every { jdbc.querySingle(match { it.contains("count(*)") }) } returns mapOf("cnt" to 2L)
        every { jdbc.querySingle(match { it.contains("type_name") }, *anyVararg()) } returns
            mapOf("type_name" to "nvarchar")
        every { jdbc.queryList(capture(sql), any()) } returns emptyList()
        adapter.topValues(pool, "t", "c", 5)
        sql.captured.contains("GROUP BY [c] ") shouldBe true

        every { jdbc.querySingle(match { it.contains("type_name") }, *anyVararg()) } returns
            mapOf("type_name" to "geometry")
        adapter.topValues(pool, "t", "c", 5)
        sql.captured.contains("GROUP BY CAST([c] AS NVARCHAR(4000))") shouldBe true
    }

    test("every target type renders a predicate, and a fully compatible column has no examples") {
        every { jdbc.querySingle(match { it.contains("type_name") }, *anyVararg()) } returns
            mapOf("type_name" to "nvarchar")
        every { jdbc.querySingle(match { !it.contains("type_name") }) } returns
            mapOf("checked" to 3L, "compat" to 3L, "incompat" to 0L)
        val all = adapter.targetTypeCompatibility(pool, "users", "code", TargetLogicalType.entries)
        all shouldHaveSize TargetLogicalType.entries.size
        all.forEach { it.exampleInvalidValues.shouldBeEmpty() }
    }
})
