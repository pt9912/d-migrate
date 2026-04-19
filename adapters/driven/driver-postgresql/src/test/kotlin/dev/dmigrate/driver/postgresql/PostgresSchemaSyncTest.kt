package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import java.sql.Connection

class PostgresSchemaSyncTest : FunSpec({

    val conn = mockk<Connection>(relaxUnitFun = true)
    val jdbc = mockk<JdbcOperations>()
    val sync = PostgresSchemaSync(jdbcFactory = { jdbc })

    // Mock for currentSchema used by assertNoUserTriggers → schemaOrCurrent
    beforeEach { clearMocks(jdbc) }

    // ── reseedGenerators ───────────────────────────

    test("reseedGenerators with empty columns returns empty") {
        sync.reseedGenerators(conn, "public.users", emptyList()).shouldBeEmpty()
    }

    test("reseedGenerators adjusts sequence for serial column") {
        every { jdbc.querySingle(match { it.contains("pg_get_serial_sequence") }, any(), any()) } returns
            mapOf("pg_get_serial_sequence" to "public.users_id_seq")
        every { jdbc.querySingle(match { it.contains("MAX") }, *anyVararg()) } returns
            mapOf("max_val" to 42L)
        every { jdbc.querySingle(match { it.contains("setval") }, any(), any()) } returns
            mapOf("setval" to 42L)

        val cols = listOf(ColumnDescriptor("id", nullable = false))
        val result = sync.reseedGenerators(conn, "public.users", cols)

        result shouldHaveSize 1
        result[0].table shouldBe "public.users"
        result[0].column shouldBe "id"
        result[0].sequenceName shouldBe "public.users_id_seq"
        result[0].newValue shouldBe 43
    }

    test("reseedGenerators skips column without sequence") {
        every { jdbc.querySingle(match { it.contains("pg_get_serial_sequence") }, any(), any()) } returns
            mapOf("pg_get_serial_sequence" to null)

        val cols = listOf(ColumnDescriptor("name", nullable = true))
        sync.reseedGenerators(conn, "public.users", cols).shouldBeEmpty()
    }

    test("reseedGenerators skips column with null max value") {
        every { jdbc.querySingle(match { it.contains("pg_get_serial_sequence") }, any(), any()) } returns
            mapOf("pg_get_serial_sequence" to "public.users_id_seq")
        every { jdbc.querySingle(match { it.contains("MAX") }, *anyVararg()) } returns
            mapOf("max_val" to null)

        val cols = listOf(ColumnDescriptor("id", nullable = false))
        sync.reseedGenerators(conn, "public.users", cols).shouldBeEmpty()
    }

    test("reseedGenerators handles multiple columns") {
        every { jdbc.querySingle(match { it.contains("pg_get_serial_sequence") }, eq("\"public\".\"t\""), eq("id")) } returns
            mapOf("pg_get_serial_sequence" to "public.t_id_seq")
        every { jdbc.querySingle(match { it.contains("pg_get_serial_sequence") }, eq("\"public\".\"t\""), eq("counter")) } returns
            mapOf("pg_get_serial_sequence" to null)
        every { jdbc.querySingle(match { it.contains("MAX") }, *anyVararg()) } returns
            mapOf("max_val" to 10L)
        every { jdbc.querySingle(match { it.contains("setval") }, any(), any()) } returns
            mapOf("setval" to 10L)

        val cols = listOf(ColumnDescriptor("id", nullable = false), ColumnDescriptor("counter", nullable = false))
        val result = sync.reseedGenerators(conn, "public.t", cols)

        result shouldHaveSize 1
        result[0].column shouldBe "id"
    }

    // ── disableTriggers / enableTriggers ───────────

    test("disableTriggers executes ALTER TABLE DISABLE TRIGGER") {
        every { conn.autoCommit } returns true
        every { jdbc.execute(match { it.contains("DISABLE TRIGGER USER") }) } returns 0

        sync.disableTriggers(conn, "public.users")

        verify { jdbc.execute(match { it.contains("DISABLE TRIGGER USER") }) }
        verify { conn.commit() }
    }

    test("enableTriggers executes ALTER TABLE ENABLE TRIGGER") {
        every { conn.autoCommit } returns true
        every { jdbc.execute(match { it.contains("ENABLE TRIGGER USER") }) } returns 0

        sync.enableTriggers(conn, "public.users")

        verify { jdbc.execute(match { it.contains("ENABLE TRIGGER USER") }) }
        verify { conn.commit() }
    }

    test("disableTriggers rolls back on failure") {
        every { conn.autoCommit } returns true
        every { jdbc.execute(any()) } throws RuntimeException("DDL failed")

        shouldThrow<RuntimeException> {
            sync.disableTriggers(conn, "public.users")
        }.message shouldBe "DDL failed"

        verify { conn.rollback() }
    }

    // ── assertNoUserTriggers ──────────────────────

    test("assertNoUserTriggers passes when no triggers exist") {
        // Mock currentSchema for schemaOrCurrent
        val stmt = mockk<java.sql.Statement>(relaxUnitFun = true)
        val rs = mockk<java.sql.ResultSet>(relaxUnitFun = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery("SELECT current_schema()") } returns rs
        every { rs.next() } returns true
        every { rs.getString(1) } returns "public"

        every { jdbc.querySingle(match { it.contains("pg_trigger") }, any(), any()) } returns null

        sync.assertNoUserTriggers(conn, "public.users")
    }

    test("assertNoUserTriggers throws when trigger exists") {
        val stmt = mockk<java.sql.Statement>(relaxUnitFun = true)
        val rs = mockk<java.sql.ResultSet>(relaxUnitFun = true)
        every { conn.createStatement() } returns stmt
        every { stmt.executeQuery("SELECT current_schema()") } returns rs
        every { rs.next() } returns true
        every { rs.getString(1) } returns "public"

        every { jdbc.querySingle(match { it.contains("pg_trigger") }, any(), any()) } returns
            mapOf("tgname" to "trg_audit")

        val ex = shouldThrow<IllegalStateException> {
            sync.assertNoUserTriggers(conn, "public.users")
        }
        ex.message shouldContain "trg_audit"
        ex.message shouldContain "triggerMode=strict"
    }
})
