package dev.dmigrate.driver.mysql

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
 * 0.9.7 preserve-current-value Sub-Slice C: pins the JDBC-mock
 * contract for [MysqlSequenceCurrentValueProbe]. Integration coverage
 * against a live MySQL container lives in
 * `MysqlSequenceCurrentValueProbeIntegrationTest`; this test pins
 * SQL shape + the six outcome branches without needing Docker.
 */
class MysqlSequenceCurrentValueProbeTest : FunSpec({

    fun mysqlRef(name: String) =
        SequenceObjectRef(name = name, schema = null, dialect = RenameProjectionDialect.MYSQL)

    fun mockConnRow(
        nextValue: Long,
        managedBy: String?,
        formatVersion: String?,
        rowCount: Int = 1,
        sqlSlot: CapturingSlot<String> = slot(),
    ): Connection {
        val rs = mockk<ResultSet>(relaxed = true)
        // First `next()` returns true if rowCount >= 1; second
        // returns true only if rowCount > 1 (the ambiguous-row path);
        // third returns false (end of cursor).
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
        val conn = mockConnRow(42L, "d-migrate", "mysql-sequence-v1", sqlSlot = sqlSlot)
        MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("order_seq"))
        sqlSlot.captured shouldBe
            "SELECT `next_value`, `managed_by`, `format_version` " +
                "FROM `dmg_sequences` WHERE `name` = 'order_seq'"
    }

    test("managed d-migrate row → Read with isCalled=null and parsed format_version") {
        val conn = mockConnRow(100L, "d-migrate", "mysql-sequence-v1")
        val result = MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
        result.value shouldBe 100L
        result.matchedRows shouldBe 1
        result.isCalled shouldBe null
        result.managedBy shouldBe "d-migrate"
        result.formatVersion shouldBe 1
    }

    test("empty ResultSet → NotFound (helper table exists, but no row for this name)") {
        val conn = mockConnEmptyResult()
        val result = MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("not_there"))
        result shouldBe SequenceCurrentValueProbeResult.NotFound
    }

    test("error 1146 (no_such_table) → NotFound (helper table not bootstrapped)") {
        val conn = mockConnThrows(MysqlSequenceCurrentValueProbe.MYSQL_ERR_NO_SUCH_TABLE)
        val result = MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("order_seq"))
        result shouldBe SequenceCurrentValueProbeResult.NotFound
    }

    test("error 1142 (tableaccess_denied) → Failed(PROBE_PERMISSION_DENIED)") {
        val conn = mockConnThrows(
            MysqlSequenceCurrentValueProbe.MYSQL_ERR_TABLEACCESS_DENIED,
            "SELECT command denied to user 'svc'@'localhost' for table 'dmg_sequences'",
        )
        val result = MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe MysqlSequenceCurrentValueProbe.CODE_PERMISSION_DENIED
        result.message shouldContain "SELECT command denied"
    }

    test("other SQLException → Failed(PROBE_QUERY_FAILED)") {
        val conn = mockConnThrows(2003, "Can't connect to MySQL server")
        val result = MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe MysqlSequenceCurrentValueProbe.CODE_QUERY_FAILED
        result.message shouldContain "Can't connect"
    }

    test("managed_by mismatch → Failed(PROBE_UNMANAGED_ROW) — operator-inserted row stays untouched") {
        val conn = mockConnRow(50L, "other_tool", "mysql-sequence-v1")
        val result = MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe MysqlSequenceCurrentValueProbe.CODE_UNMANAGED_ROW
        result.message shouldContain "other_tool"
        result.message shouldContain "order_seq"
    }

    test("managed_by NULL → Failed(PROBE_UNMANAGED_ROW) — defensive against orphaned rows") {
        val conn = mockConnRow(50L, null, "mysql-sequence-v1")
        val result = MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe MysqlSequenceCurrentValueProbe.CODE_UNMANAGED_ROW
    }

    test("format_version outside SUPPORTED set → Failed(PROBE_UNKNOWN_FORMAT_VERSION)") {
        val conn = mockConnRow(50L, "d-migrate", "mysql-sequence-v99")
        val result = MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe MysqlSequenceCurrentValueProbe.CODE_UNKNOWN_FORMAT_VERSION
        result.message shouldContain "mysql-sequence-v99"
    }

    test("multi-row result (PK invariant broken) → Failed(PROBE_AMBIGUOUS_ROW)") {
        val conn = mockConnRow(50L, "d-migrate", "mysql-sequence-v1", rowCount = 2)
        val result = MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("order_seq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe MysqlSequenceCurrentValueProbe.CODE_AMBIGUOUS_ROW
        result.message shouldContain "order_seq"
    }

    test("Statement and ResultSet are closed on success path (resource leak guard)") {
        val sqlSlot = slot<String>()
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.next() } returnsMany listOf(true, false, false)
        every { rs.getLong("next_value") } returns 1L
        every { rs.getString("managed_by") } returns "d-migrate"
        every { rs.getString("format_version") } returns "mysql-sequence-v1"
        every { rs.close() } returns Unit
        val stmt = mockk<Statement>(relaxed = true)
        every { stmt.executeQuery(capture(sqlSlot)) } returns rs
        every { stmt.close() } returns Unit
        val conn = mockk<Connection>()
        every { conn.createStatement() } returns stmt

        MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("order_seq"))

        verify(exactly = 1) { rs.close() }
        verify(exactly = 1) { stmt.close() }
    }

    test("sequence name with apostrophe is single-quote-escaped (no SQL injection through identifier)") {
        val sqlSlot = slot<String>()
        val conn = mockConnRow(1L, "d-migrate", "mysql-sequence-v1", sqlSlot = sqlSlot)
        MysqlSequenceCurrentValueProbe.probe(conn, mysqlRef("weird'name"))
        sqlSlot.captured shouldContain "`name` = 'weird''name'"
    }
})
