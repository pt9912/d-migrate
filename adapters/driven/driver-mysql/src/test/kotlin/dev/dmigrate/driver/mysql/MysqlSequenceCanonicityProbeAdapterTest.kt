package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.MysqlSequenceCanonicityKind
import dev.dmigrate.driver.MysqlSequenceCanonicityStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice A: smoke tests for the
 * probe adapter using mocked JDBC primitives. Full integration
 * against a live MySQL via testcontainers belongs to Sub-Slice C;
 * the goal here is to pin (a) the status decision tree per
 * canonical-object kind and (b) that SQLException routing maps
 * to `PROBE_RUNTIME_ERROR` vs. `MISSING` correctly.
 */
class MysqlSequenceCanonicityProbeAdapterTest : FunSpec({

    test("probeSupportTable: missing table → MISSING") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } returns rs
        every { rs.next() } returns false

        val decl = MysqlSequenceCanonicityProbeAdapter(conn).probeSupportTable("op-1")
        decl.status shouldBe MysqlSequenceCanonicityStatus.MISSING
        decl.kind shouldBe MysqlSequenceCanonicityKind.SUPPORT_TABLE
        decl.objectName shouldBe MysqlSequenceNaming.SUPPORT_TABLE
    }

    test("probeSupportTable: SQLException → PROBE_RUNTIME_ERROR with the exception message") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } throws SQLException("permission denied", "42501", 1142)

        val decl = MysqlSequenceCanonicityProbeAdapter(conn).probeSupportTable("op-1")
        decl.status shouldBe MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR
        decl.problem shouldBe "permission denied"
    }

    test("probeRoutine: missing routine → MISSING (MySQL error 1305 trapped)") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } throws SQLException(
            "FUNCTION `dmg_nextval` does not exist", "42000", 1305,
        )

        val decl = MysqlSequenceCanonicityProbeAdapter(conn)
            .probeRoutine("op-1", MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE)
        decl.status shouldBe MysqlSequenceCanonicityStatus.MISSING
        decl.objectName shouldBe MysqlSequenceNaming.NEXTVAL_ROUTINE
    }

    test("probeRoutine: body without canonical marker → DRIFT") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } returns rs
        every { rs.next() } returns true
        every { rs.getString("Create Function") } returns
            "CREATE FUNCTION `dmg_nextval`(seq_name VARCHAR(255)) RETURNS BIGINT BEGIN RETURN 0; END"

        val decl = MysqlSequenceCanonicityProbeAdapter(conn)
            .probeRoutine("op-1", MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE)
        decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
        decl.driftField shouldBe "body_marker"
    }

    test("probeRoutine: marker intact but body changed → DRIFT body_signature") {
        // E.3 Sub-Slice F follow-up: an operator who keeps the
        // canonical marker comment but rewrites the body (extra
        // logging, different increment semantics) used to pass the
        // probe. The body-signature check catches it.
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } returns rs
        every { rs.next() } returns true
        every { rs.getString("Create Function") } returns
            "CREATE FUNCTION `dmg_nextval`(seq_name VARCHAR(255)) RETURNS BIGINT " +
                "BEGIN /* d-migrate:mysql-sequence-v1 object=nextval */ RETURN 1; END"

        val decl = MysqlSequenceCanonicityProbeAdapter(conn)
            .probeRoutine("op-1", MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE)
        decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
        decl.driftField shouldBe "body_signature"
    }

    test("probeSequenceRow: matching managed fields → CANONICAL") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } returns rs
        every { rs.next() } returns true
        every { rs.getLong("increment_by") } returns 1L
        every { rs.getLong("min_value") } returns 1L
        every { rs.getLong("max_value") } returns 999L
        every { rs.getInt("cycle_enabled") } returns 0
        every { rs.getInt("cache_size") } returns 10
        every { rs.wasNull() } returns false

        val decl = MysqlSequenceCanonicityProbeAdapter(conn).probeSequenceRow(
            operationId = "op-1",
            sequenceName = "order_seq",
            expectedIncrement = 1L,
            expectedMinValue = 1L,
            expectedMaxValue = 999L,
            expectedCycle = false,
            expectedCache = 10,
        )
        decl.status shouldBe MysqlSequenceCanonicityStatus.CANONICAL
        decl.driftField shouldBe null
    }

    test("probeSequenceRow: row absent → MISSING") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } returns rs
        every { rs.next() } returns false

        val decl = MysqlSequenceCanonicityProbeAdapter(conn).probeSequenceRow(
            operationId = "op-1",
            sequenceName = "order_seq",
            expectedIncrement = 1L,
            expectedMinValue = null,
            expectedMaxValue = null,
            expectedCycle = false,
            expectedCache = null,
        )
        decl.status shouldBe MysqlSequenceCanonicityStatus.MISSING
    }

    test("probeSequenceRow: increment_by mismatch → DRIFT with field name") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } returns rs
        every { rs.next() } returns true
        every { rs.getLong("increment_by") } returns 5L
        every { rs.getLong("min_value") } returns 1L
        every { rs.getLong("max_value") } returns 999L
        every { rs.getInt("cycle_enabled") } returns 0
        every { rs.getInt("cache_size") } returns 10
        every { rs.wasNull() } returns false

        val decl = MysqlSequenceCanonicityProbeAdapter(conn).probeSequenceRow(
            operationId = "op-1",
            sequenceName = "order_seq",
            expectedIncrement = 1L,
            expectedMinValue = 1L,
            expectedMaxValue = 999L,
            expectedCycle = false,
            expectedCache = 10,
        )
        decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
        decl.driftField shouldBe "increment_by"
        decl.expected shouldBe "1"
        decl.actual shouldBe "5"
    }

    test("probeSupportTrigger: missing trigger → MISSING (MySQL error 1360 trapped)") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } throws SQLException(
            "Trigger does not exist", "HY000", 1360,
        )

        val decl = MysqlSequenceCanonicityProbeAdapter(conn).probeSupportTrigger(
            operationId = "op-1",
            triggerName = "dmg_seq_orders_id_abc123_bi",
            expectedSequenceName = "order_seq",
        )
        decl.status shouldBe MysqlSequenceCanonicityStatus.MISSING
    }

    test("probeSupportTrigger: body marker + dmg_nextval('expected') call → CANONICAL") {
        // Marker AND the actual nextval call resolve the right
        // sequence; the body-signature normaliser ignores
        // backticks / whitespace so the body shape doesn't have to
        // match the template byte-for-byte (that's the integration
        // test's job; here we pin the contract).
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } returns rs
        every { rs.next() } returns true
        every { rs.getString("SQL Original Statement") } returns
            "CREATE TRIGGER `dmg_seq_…` BEFORE INSERT ON `orders` FOR EACH ROW BEGIN " +
                "/* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=order_seq " +
                "table=orders column=id */ " +
                "IF NEW.`id` IS NULL THEN SET NEW.`id` = `dmg_nextval`('order_seq'); END IF; END"

        val decl = MysqlSequenceCanonicityProbeAdapter(conn).probeSupportTrigger(
            operationId = "op-1",
            triggerName = "dmg_seq_orders_id_abc123_bi",
            expectedSequenceName = "order_seq",
        )
        decl.status shouldBe MysqlSequenceCanonicityStatus.CANONICAL
    }

    test("probeSupportTrigger: marker intact but dmg_nextval resolves OTHER sequence → DRIFT sequence_reference") {
        // Plan-Doc §1.4: the operator may have manually moved the
        // column to a different sequence while leaving the marker
        // pointing at the original. The sequence_reference probe
        // catches this exact case.
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } returns rs
        every { rs.next() } returns true
        every { rs.getString("SQL Original Statement") } returns
            "CREATE TRIGGER `dmg_seq_…` BEFORE INSERT ON `orders` FOR EACH ROW BEGIN " +
                "/* d-migrate:mysql-sequence-v1 object=sequence-trigger sequence=order_seq " +
                "table=orders column=id */ " +
                "IF NEW.`id` IS NULL THEN SET NEW.`id` = `dmg_nextval`('different_seq'); END IF; END"

        val decl = MysqlSequenceCanonicityProbeAdapter(conn).probeSupportTrigger(
            operationId = "op-1",
            triggerName = "dmg_seq_orders_id_abc123_bi",
            expectedSequenceName = "order_seq",
        )
        decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
        decl.driftField shouldBe "sequence_reference"
    }

    test("probeSupportTrigger: body marker mentions OTHER sequence → DRIFT") {
        val conn = mockk<Connection>(relaxed = true)
        val stmt = mockk<Statement>(relaxed = true)
        val rs = mockk<ResultSet>(relaxed = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery(any()) } returns rs
        every { rs.next() } returns true
        every { rs.getString("SQL Original Statement") } returns
            "CREATE TRIGGER ... /* d-migrate:mysql-sequence-v1 object=sequence-trigger " +
                "sequence=invoice_seq table=orders column=id */ END"

        val decl = MysqlSequenceCanonicityProbeAdapter(conn).probeSupportTrigger(
            operationId = "op-1",
            triggerName = "dmg_seq_orders_id_abc123_bi",
            expectedSequenceName = "order_seq",
        )
        decl.status shouldBe MysqlSequenceCanonicityStatus.DRIFT
        decl.driftField shouldBe "body_marker"
        decl.expected shouldNotBe null
    }
})
