package dev.dmigrate.driver.oracle

import dev.dmigrate.driver.DatabaseDialect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import oracle.sql.TIMESTAMPTZ
import java.sql.Blob
import java.sql.Clob
import java.sql.Connection
import java.time.OffsetDateTime

/**
 * Die Streaming-Politik des Readers (Quoting, fetchSize, Transaktions-
 * Verhalten, CLOB/BLOB-Materialisierung, TIMESTAMPTZ-Konvertierung) sind
 * `protected` Hooks der Basisklasse -- diese Test-Ableitung macht sie
 * sichtbar, ohne die Produktions-API zu oeffnen.
 */
private class ProbeReader(fetchSizeOverride: Int? = null) : OracleDataReader(fetchSizeOverride) {
    val probeFetchSize: Int get() = fetchSize
    val probeNeedsAutoCommitFalse: Boolean get() = needsAutoCommitFalse
    fun probeQuote(name: String): String = quoteIdentifier(name)
    fun probeSelectSql(table: String): String = buildSelectQuery(table, null).sql
    fun probeMapValue(value: Any?, conn: Connection = mockk()): Any? = mapValue(value, conn)
}

class OracleDataReaderTest : FunSpec({

    test("dialect and streaming defaults") {
        val reader = ProbeReader()
        reader.dialect shouldBe DatabaseDialect.ORACLE
        reader.probeFetchSize shouldBe 1_000
        // Server-Cursor-Fetching genuegt; keine offene Transaktion noetig.
        reader.probeNeedsAutoCommitFalse shouldBe false
    }

    test("fetch-size override wins (LN-005)") {
        ProbeReader(fetchSizeOverride = 250).probeFetchSize shouldBe 250
    }

    test("identifiers are double-quoted") {
        ProbeReader().probeQuote("my table") shouldBe "\"my table\""
    }

    test("select query double-quotes the qualified table path") {
        ProbeReader().probeSelectSql("sales.orders") shouldBe "SELECT * FROM \"sales\".\"orders\""
    }

    test("CLOB values are materialized to String while the cursor still holds the row") {
        val clob = mockk<Clob>(relaxUnitFun = true)
        every { clob.length() } returns 11L
        every { clob.getSubString(1, 11) } returns "hello world"

        ProbeReader().probeMapValue(clob) shouldBe "hello world"
        verify { clob.free() }
    }

    test("BLOB values are materialized to ByteArray while the cursor still holds the row") {
        val blob = mockk<Blob>(relaxUnitFun = true)
        val bytes = byteArrayOf(1, 2, 3)
        every { blob.length() } returns 3L
        every { blob.getBytes(1, 3) } returns bytes

        ProbeReader().probeMapValue(blob) shouldBe bytes
        verify { blob.free() }
    }

    test("a failure freeing the LOB locator does not hide the materialized value") {
        val clob = mockk<Clob>(relaxUnitFun = true)
        every { clob.length() } returns 4L
        every { clob.getSubString(1, 4) } returns "text"
        every { clob.free() } throws RuntimeException("locator already closed")

        ProbeReader().probeMapValue(clob) shouldBe "text"
    }

    test("TIMESTAMPTZ is converted to a standard OffsetDateTime using the connection") {
        // Real gegen den Testcontainer verifiziert: getObject() liefert fuer
        // TIMESTAMP WITH TIME ZONE das treibereigene TIMESTAMPTZ, nicht
        // OffsetDateTime; die Konvertierung braucht zwingend die Connection.
        val conn = mockk<Connection>()
        val tstz = mockk<TIMESTAMPTZ>()
        val expected = OffsetDateTime.parse("2026-08-22T14:00+02:00")
        every { tstz.offsetDateTimeValue(conn) } returns expected

        ProbeReader().probeMapValue(tstz, conn) shouldBe expected
    }

    test("standard values pass through unchanged") {
        val reader = ProbeReader()
        reader.probeMapValue("text") shouldBe "text"
        reader.probeMapValue(42) shouldBe 42
        reader.probeMapValue(null) shouldBe null
    }
})
