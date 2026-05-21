package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * 0.9.7 preserve-current-value Sub-Slice B: pins the JDBC-mock
 * contract for [PostgresSequenceCurrentValueProbe]. Integration
 * coverage against a real PG container lives in
 * `PostgresSequenceCurrentValueProbeIntegrationTest`; the unit test
 * here pins SQL shape + SQLSTATE routing without needing Docker.
 */
class PostgresSequenceCurrentValueProbeTest : FunSpec({

    fun pgRef(name: String, schema: String? = null) =
        SequenceObjectRef(name = name, schema = schema, dialect = RenameProjectionDialect.POSTGRESQL)

    /**
     * Stitches a [Connection] / [Statement] / [ResultSet] mock chain
     * that returns one row (`last_value`, `is_called`) and captures
     * the executed SQL into [sqlSlot]. SQLException-throwing variants
     * use a different builder so they don't carry a stub ResultSet.
     */
    fun mockConnRead(
        lastValue: Long,
        isCalled: Boolean,
        sqlSlot: io.mockk.CapturingSlot<String> = slot(),
    ): Connection {
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.next() } returnsMany listOf(true, false)
        every { rs.getLong("last_value") } returns lastValue
        every { rs.getBoolean("is_called") } returns isCalled
        every { rs.close() } returns Unit
        val stmt = mockk<Statement>(relaxed = true)
        every { stmt.executeQuery(capture(sqlSlot)) } returns rs
        every { stmt.close() } returns Unit
        val conn = mockk<Connection>()
        every { conn.createStatement() } returns stmt
        return conn
    }

    fun mockConnThrows(sqlState: String, message: String = "boom"): Connection {
        val stmt = mockk<Statement>(relaxed = true)
        every { stmt.executeQuery(any()) } throws SQLException(message, sqlState)
        every { stmt.close() } returns Unit
        val conn = mockk<Connection>()
        every { conn.createStatement() } returns stmt
        return conn
    }

    fun mockConnEmptyResult(): Connection {
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.next() } returns false
        every { rs.close() } returns Unit
        val stmt = mockk<Statement>(relaxed = true)
        every { stmt.executeQuery(any()) } returns rs
        every { stmt.close() } returns Unit
        val conn = mockk<Connection>()
        every { conn.createStatement() } returns stmt
        return conn
    }

    test("unqualified sequence: SELECT last_value, is_called FROM \"<name>\"") {
        val sqlSlot = slot<String>()
        val conn = mockConnRead(lastValue = 42L, isCalled = true, sqlSlot = sqlSlot)
        val result = PostgresSequenceCurrentValueProbe.probe(conn, pgRef("order_seq"))
        sqlSlot.captured shouldBe "SELECT last_value, is_called FROM \"order_seq\""
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
        result.value shouldBe 42L
        result.isCalled shouldBe true
        result.matchedRows shouldBe 1
    }

    test("schema-qualified sequence: SELECT … FROM \"<schema>\".\"<name>\"") {
        val sqlSlot = slot<String>()
        val conn = mockConnRead(lastValue = 1L, isCalled = false, sqlSlot = sqlSlot)
        PostgresSequenceCurrentValueProbe.probe(conn, pgRef("order_seq", schema = "audit"))
        sqlSlot.captured shouldBe "SELECT last_value, is_called FROM \"audit\".\"order_seq\""
    }

    test("isCalled = false propagates through the Read outcome") {
        val conn = mockConnRead(lastValue = 1L, isCalled = false)
        val result = PostgresSequenceCurrentValueProbe.probe(conn, pgRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
        result.isCalled shouldBe false
    }

    test("SQLSTATE 42P01 (undefined_table) maps to NotFound") {
        val conn = mockConnThrows(
            PostgresSequenceCurrentValueProbe.SQLSTATE_UNDEFINED_TABLE,
            "relation \"order_seq\" does not exist",
        )
        val result = PostgresSequenceCurrentValueProbe.probe(conn, pgRef("order_seq"))
        result shouldBe SequenceCurrentValueProbeResult.NotFound
    }

    test("SQLSTATE 42501 (insufficient_privilege) maps to Failed(PROBE_PERMISSION_DENIED)") {
        val conn = mockConnThrows(
            PostgresSequenceCurrentValueProbe.SQLSTATE_INSUFFICIENT_PRIVILEGE,
            "permission denied for sequence order_seq",
        )
        val result = PostgresSequenceCurrentValueProbe.probe(conn, pgRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe PostgresSequenceCurrentValueProbe.CODE_PERMISSION_DENIED
        result.message shouldContain "permission denied"
    }

    test("other SQLException (no recognised SQLSTATE) maps to Failed(PROBE_QUERY_FAILED)") {
        val conn = mockConnThrows(sqlState = "08006", message = "connection lost")
        val result = PostgresSequenceCurrentValueProbe.probe(conn, pgRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe PostgresSequenceCurrentValueProbe.CODE_QUERY_FAILED
        result.message shouldContain "connection lost"
    }

    test("empty ResultSet surfaces as Failed(PROBE_QUERY_FAILED) — pg sequences never return 0 rows") {
        val conn = mockConnEmptyResult()
        val result = PostgresSequenceCurrentValueProbe.probe(conn, pgRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe PostgresSequenceCurrentValueProbe.CODE_QUERY_FAILED
        result.message shouldContain "0 rows"
    }

    test("Statement and ResultSet are closed even on success path (resource leak guard)") {
        val sqlSlot = slot<String>()
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.next() } returnsMany listOf(true, false)
        every { rs.getLong("last_value") } returns 1L
        every { rs.getBoolean("is_called") } returns true
        every { rs.close() } returns Unit
        val stmt = mockk<Statement>(relaxed = true)
        every { stmt.executeQuery(capture(sqlSlot)) } returns rs
        every { stmt.close() } returns Unit
        val conn = mockk<Connection>()
        every { conn.createStatement() } returns stmt

        PostgresSequenceCurrentValueProbe.probe(conn, pgRef("order_seq"))

        verify(exactly = 1) { rs.close() }
        verify(exactly = 1) { stmt.close() }
    }
})
