package dev.dmigrate.driver.mssql

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

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
})
