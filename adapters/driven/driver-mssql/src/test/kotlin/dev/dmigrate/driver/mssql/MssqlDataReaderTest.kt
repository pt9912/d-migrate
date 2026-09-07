package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/**
 * Die Streaming-Politik des Readers (Quoting, fetchSize, Transaktions- und
 * Geometrie-Verhalten) sind `protected` Hooks der Basisklasse — diese
 * Test-Ableitung macht sie sichtbar, ohne die Produktions-API zu oeffnen.
 */
private class ProbeReader(fetchSizeOverride: Int? = null) : MssqlDataReader(fetchSizeOverride) {
    val probeFetchSize: Int get() = fetchSize
    val probeNeedsAutoCommitFalse: Boolean get() = needsAutoCommitFalse
    val probeSupportsGeometryRead: Boolean get() = supportsGeometryRead
    fun probeQuote(name: String): String = quoteIdentifier(name)
    fun probeGeometryExpression(quoted: String): String = geometryReadExpression(quoted)
    fun probeIsGeometryType(name: String): Boolean = isGeometryTypeName(name)
    fun probeSelectSql(table: String): String = buildSelectQuery(table, null).sql
    fun probeMapValue(value: Any?): Any? = mapValue(value, io.mockk.mockk())
}

class MssqlDataReaderTest : FunSpec({

    test("dialect and streaming defaults") {
        val reader = ProbeReader()
        reader.dialect shouldBe DatabaseDialect.MSSQL
        reader.probeFetchSize shouldBe 1_000
        // Adaptive Pufferung: keine offene Transaktion, sonst haelt der Export Shared Locks.
        reader.probeNeedsAutoCommitFalse shouldBe false
        reader.probeSupportsGeometryRead shouldBe true
    }

    test("fetch-size override wins (LN-005)") {
        ProbeReader(fetchSizeOverride = 250).probeFetchSize shouldBe 250
    }

    test("identifiers are bracket-quoted and geometry columns project as WKB") {
        val reader = ProbeReader()
        reader.probeQuote("my table") shouldBe "[my table]"
        reader.probeGeometryExpression("[loc]") shouldBe "[loc].STAsBinary()"
        reader.probeIsGeometryType("geometry") shouldBe true
        reader.probeIsGeometryType("geography") shouldBe true
        reader.probeIsGeometryType("nvarchar") shouldBe false
        // `point` ist in SQL Server kein eigener Typ (anders als in PostgreSQL).
        reader.probeIsGeometryType("point") shouldBe false
    }

    test("select query brackets the qualified table path") {
        ProbeReader().probeSelectSql("sales.orders") shouldBe "SELECT * FROM [sales].[orders]"
    }

    test("driver-owned DATETIMEOFFSET becomes a standard OffsetDateTime") {
        val reader = ProbeReader()
        val offsetDateTime = java.time.OffsetDateTime.parse("2026-08-22T14:00+02:00")
        val driverValue = microsoft.sql.DateTimeOffset.valueOf(
            java.sql.Timestamp.from(offsetDateTime.toInstant()), 120,
        )
        // Ohne diese Naht landet der Treibertyp im neutralen Chunk-Strom und
        // `data transfer --verify` meldet jede Tabelle als "inconclusive".
        reader.probeMapValue(driverValue) shouldBe offsetDateTime
        // Standardwerte bleiben unveraendert.
        reader.probeMapValue("text") shouldBe "text"
        reader.probeMapValue(null) shouldBe null
    }

    test("the SRID probe reads DISTINCT over the values, per column") {
        val captured = mutableListOf<String>()
        val (reader, pool) = sridRig(captured, mapOf("loc" to listOf(25832), "area" to listOf(4326, 25832)))

        reader.geometrySrids(pool, "sales.orders", listOf("loc", "area")) shouldBe
            mapOf("loc" to listOf(25832), "area" to listOf(4326, 25832))

        // Ein `TOP (1)` saehe eine gemischte Spalte einheitlich; nur DISTINCT
        // ueber alle Werte belegt, dass es wirklich nur eine SRID gibt.
        captured shouldContainExactly listOf(
            "SELECT DISTINCT [loc].STSrid AS srid FROM [sales].[orders] WHERE [loc] IS NOT NULL",
            "SELECT DISTINCT [area].STSrid AS srid FROM [sales].[orders] WHERE [area] IS NOT NULL",
        )
    }

    test("a column without a single non-empty value is absent, and no column means no query") {
        val captured = mutableListOf<String>()
        val (reader, pool) = sridRig(captured, mapOf("loc" to emptyList()))
        reader.geometrySrids(pool, "orders", listOf("loc")) shouldBe emptyMap()

        val untouched = mutableListOf<String>()
        val (bare, barePool) = sridRig(untouched, emptyMap())
        bare.geometrySrids(barePool, "orders", emptyList()) shouldBe emptyMap()
        untouched.shouldBeEmpty()
    }
})

/**
 * Stellt eine Verbindung, deren `createStatement().executeQuery(sql)` das in
 * [answers] hinterlegte Ergebnis der abgefragten Spalte liefert, und schreibt
 * jedes ausgefuehrte SQL nach [captured].
 */
private fun sridRig(
    captured: MutableList<String>,
    answers: Map<String, List<Int>>,
): Pair<MssqlDataReader, ConnectionPool> {
    val statement = mockk<Statement>(relaxUnitFun = true)
    every { statement.executeQuery(any()) } answers {
        val sql = firstArg<String>()
        captured += sql
        val column = answers.keys.first { sql.contains("[$it].STSrid") }
        resultSetOf(answers.getValue(column))
    }
    val conn = mockk<Connection>(relaxUnitFun = true) {
        every { createStatement() } returns statement
    }
    val pool = mockk<ConnectionPool> {
        every { borrow() } returns JdbcDatabaseConnection(conn)
    }
    return MssqlDataReader() to pool
}

private fun resultSetOf(srids: List<Int>): ResultSet {
    var index = -1
    return mockk<ResultSet>(relaxUnitFun = true) {
        every { next() } answers { ++index < srids.size }
        every { getInt("srid") } answers { srids[index] }
        every { wasNull() } returns false
    }
}
