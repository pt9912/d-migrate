package dev.dmigrate.driver.mssql

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
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * Haelt die Abfrage-Form und die Fehler-Zuordnung von
 * [MssqlSequenceCurrentValueProbe] fest, ohne Docker.
 *
 * `dialect` ist in den Referenzen ein Platzhalter: die Probe liest das Feld
 * nicht, und `RenameProjectionDialect` bekommt seinen MSSQL-Eintrag erst mit
 * Sub-Slice 5e.
 */
class MssqlSequenceCurrentValueProbeTest : FunSpec({

    fun ref(name: String, schema: String? = null) =
        SequenceObjectRef(name = name, schema = schema, dialect = RenameProjectionDialect.POSTGRESQL)

    fun mockConn(
        rows: List<Long>,
        sqlSlot: io.mockk.CapturingSlot<String> = slot(),
    ): Connection {
        var index = -1
        val rs = mockk<ResultSet>(relaxed = true)
        every { rs.next() } answers { index++; index < rows.size }
        every { rs.getLong("cv") } answers { rows[index] }
        every { rs.close() } returns Unit
        val stmt = mockk<Statement>(relaxed = true)
        every { stmt.executeQuery(capture(sqlSlot)) } returns rs
        val conn = mockk<Connection>(relaxed = true)
        every { conn.createStatement() } returns stmt
        return conn
    }

    test("reads current_value from the catalog view, not from the sequence itself") {
        // T-SQL kennt kein `SELECT … FROM <sequence>` — der Wert steht nur im Katalog.
        val sql = slot<String>()
        val result = MssqlSequenceCurrentValueProbe.probe(mockConn(listOf(41L), sql), ref("sq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Read>()
        result.value shouldBe 41L
        result.matchedRows shouldBe 1
        // Es gibt kein `is_called`-Aequivalent; die Ergebniszeile sagt das ehrlich.
        result.isCalled shouldBe null
        sql.captured shouldContain "FROM sys.sequences"
        sql.captured shouldContain "WHERE name = 'sq'"
    }

    test("a schema qualifies the lookup — sys.sequences is database-wide") {
        val sql = slot<String>()
        MssqlSequenceCurrentValueProbe.probe(mockConn(listOf(1L), sql), ref("sq", schema = "sales"))
        sql.captured shouldContain "SCHEMA_NAME(schema_id) = 'sales'"
    }

    test("no row means the sequence is not there") {
        MssqlSequenceCurrentValueProbe.probe(mockConn(emptyList()), ref("sq")) shouldBe
            SequenceCurrentValueProbeResult.NotFound
    }

    test("two rows are a failure, not a guess") {
        // Derselbe Name in zwei Schemata und kein Schemafilter — welche gemeint
        // ist, kann die Probe nicht wissen.
        val result = MssqlSequenceCurrentValueProbe.probe(mockConn(listOf(1L, 2L)), ref("sq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe MssqlSequenceCurrentValueProbe.CODE_QUERY_FAILED
        result.message shouldContain "more than one"
    }

    test("a permission error is told apart from any other failure") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } throws SQLException("no permission", "42000")
        val result = MssqlSequenceCurrentValueProbe.probe(conn, ref("sq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe MssqlSequenceCurrentValueProbe.CODE_PERMISSION_DENIED
    }

    test("any other SQLException becomes a query failure — the probe never throws") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } throws SQLException("connection reset", "08S01")
        val result = MssqlSequenceCurrentValueProbe.probe(conn, ref("sq"))
        result.shouldBeInstanceOf<SequenceCurrentValueProbeResult.Failed>()
        result.code shouldBe MssqlSequenceCurrentValueProbe.CODE_QUERY_FAILED
    }
})
