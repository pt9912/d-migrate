package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * 0.9.7 SQLite preserve-current-value Folge-Slice: pins the
 * JDBC-mock contract for [SqliteSequenceCurrentValueProbe]. Mirrors
 * `MysqlSequenceCurrentValueProbeTest` — same six outcome branches,
 * SQLite-specific error mappings (no_such_table via message, SQLITE_PERM
 * / SQLITE_AUTH via errorCode).
 */
class SqliteSequenceCurrentValueProbeTest : FunSpec({

    fun sqliteRef(name: String) =
        SequenceObjectRef(name = name, schema = null, dialect = RenameProjectionDialect.SQLITE)

    fun mockConnRow(
        nextValue: Long,
        managedBy: String?,
        formatVersion: String?,
        rowCount: Int = 1,
        sqlSlot: CapturingSlot<String> = slot(),
    ): Connection {
        val rs = mockk<ResultSet>(relaxed = true)
        val nextSequence = (1..3).map { idx -> idx <= rowCount }
        every { rs.next() } returnsMany nextSequence
        every { rs.getLong("next_value") } returns nextValue
        every { rs.getString("managed_by") } returns managedBy
        every { rs.getString("format_version") } returns formatVersion
        every { rs.close() } returns Unit
        val stmt = mockk<Statement>(relaxed = true)
        every { stmt.executeQuery(capture(sqlSlot)) } returns rs
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

    fun mockConnThrows(errorCode: Int, message: String = "boom"): Connection {
        val stmt = mockk<Statement>(relaxed = true)
        every { stmt.executeQuery(any()) } throws SQLException(message, null, errorCode)
        every { stmt.close() } returns Unit
        val conn = mockk<Connection>()
        every { conn.createStatement() } returns stmt
        return conn
    }

    test("SQL shape: SELECT next_value, managed_by, format_version FROM dmg_sequences WHERE name = '<key>'") {
        val sqlSlot = slot<String>()
        val conn = mockConnRow(42L, "d-migrate", "sqlite-sequence-v1", sqlSlot = sqlSlot)
        SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))
        sqlSlot.captured shouldBe
            "SELECT \"next_value\", \"managed_by\", \"format_version\" " +
                "FROM \"dmg_sequences\" WHERE \"name\" = 'order_seq'"
    }

    test("managed d-migrate row → Read with isCalled=null and parsed format_version") {
        val conn = mockConnRow(100L, "d-migrate", "sqlite-sequence-v1")
        val result = SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
        result.value shouldBe 100L
        result.matchedRows shouldBe 1
        result.isCalled shouldBe null
        result.managedBy shouldBe "d-migrate"
        result.formatVersion shouldBe 1
    }

    test("empty ResultSet → NotFound (helper table exists, but no row for this name)") {
        val conn = mockConnEmptyResult()
        val result = SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("not_there"))
        result shouldBe SequenceCurrentValueProbeResult.NotFound
    }

    test("\"no such table\" SQLException → NotFound (helper table not bootstrapped)") {
        // SQLite reports table-missing as a generic SQLITE_ERROR(1)
        // with a recognisable message; xerial-jdbc passes the text
        // through `SQLException.message`.
        val conn = mockConnThrows(1, "[SQLITE_ERROR] no such table: dmg_sequences")
        val result = SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))
        result shouldBe SequenceCurrentValueProbeResult.NotFound
    }

    test("SQLITE_PERM (3) → Failed(PROBE_PERMISSION_DENIED)") {
        val conn = mockConnThrows(
            SqliteSequenceCurrentValueProbe.SQLITE_ERR_PERM,
            "access permission denied",
        )
        val result = SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe SqliteSequenceCurrentValueProbe.CODE_PERMISSION_DENIED
        result.message shouldContain "permission denied"
    }

    test("SQLITE_AUTH (23) → Failed(PROBE_PERMISSION_DENIED)") {
        val conn = mockConnThrows(
            SqliteSequenceCurrentValueProbe.SQLITE_ERR_AUTH,
            "authorization denied",
        )
        val result = SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe SqliteSequenceCurrentValueProbe.CODE_PERMISSION_DENIED
    }

    test("other SQLException → Failed(PROBE_QUERY_FAILED)") {
        val conn = mockConnThrows(11, "database disk image is malformed")
        val result = SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe SqliteSequenceCurrentValueProbe.CODE_QUERY_FAILED
        result.message shouldContain "malformed"
    }

    test("managed_by mismatch → Failed(PROBE_UNMANAGED_ROW)") {
        val conn = mockConnRow(50L, "other_tool", "sqlite-sequence-v1")
        val result = SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe SqliteSequenceCurrentValueProbe.CODE_UNMANAGED_ROW
        result.message shouldContain "other_tool"
        result.message shouldContain "order_seq"
    }

    test("managed_by NULL → Failed(PROBE_UNMANAGED_ROW)") {
        val conn = mockConnRow(50L, null, "sqlite-sequence-v1")
        val result = SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe SqliteSequenceCurrentValueProbe.CODE_UNMANAGED_ROW
    }

    test("format_version mismatch → Failed(PROBE_UNKNOWN_FORMAT_VERSION)") {
        val conn = mockConnRow(50L, "d-migrate", "sqlite-sequence-v99")
        val result = SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe SqliteSequenceCurrentValueProbe.CODE_UNKNOWN_FORMAT_VERSION
        result.message shouldContain "sqlite-sequence-v99"
    }

    test("multi-row result → Failed(PROBE_AMBIGUOUS_ROW)") {
        val conn = mockConnRow(50L, "d-migrate", "sqlite-sequence-v1", rowCount = 2)
        val result = SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe SqliteSequenceCurrentValueProbe.CODE_AMBIGUOUS_ROW
        result.message shouldContain "order_seq"
    }

    test("Statement and ResultSet are closed on success path (resource leak guard)") {
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.next() } returnsMany listOf(true, false, false)
        every { rs.getLong("next_value") } returns 1L
        every { rs.getString("managed_by") } returns "d-migrate"
        every { rs.getString("format_version") } returns "sqlite-sequence-v1"
        every { rs.close() } returns Unit
        val stmt = mockk<Statement>(relaxed = true)
        every { stmt.executeQuery(any<String>()) } returns rs
        every { stmt.close() } returns Unit
        val conn = mockk<Connection>()
        every { conn.createStatement() } returns stmt

        SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("order_seq"))

        verify(exactly = 1) { rs.close() }
        verify(exactly = 1) { stmt.close() }
    }

    test("sequence name with apostrophe is single-quote-escaped (no SQL injection via identifier)") {
        val sqlSlot = slot<String>()
        val conn = mockConnRow(1L, "d-migrate", "sqlite-sequence-v1", sqlSlot = sqlSlot)
        SqliteSequenceCurrentValueProbe.probe(conn, sqliteRef("weird'name"))
        sqlSlot.captured shouldContain "\"name\" = 'weird''name'"
    }
})
