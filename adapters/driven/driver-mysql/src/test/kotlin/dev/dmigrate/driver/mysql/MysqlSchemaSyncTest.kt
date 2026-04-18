package dev.dmigrate.driver.mysql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.data.UnsupportedTriggerModeException
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.*
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

class MysqlSchemaSyncTest : FunSpec({

    val conn = mockk<Connection>(relaxUnitFun = true)
    val jdbc = mockk<JdbcOperations>()
    val sync = MysqlSchemaSync(jdbcFactory = { jdbc })

    // Mock lowerCaseTableNames(conn)
    val stmt = mockk<Statement>(relaxUnitFun = true)
    val rs = mockk<ResultSet>(relaxUnitFun = true)
    every { conn.createStatement() } returns stmt
    every { stmt.executeQuery("SELECT @@lower_case_table_names") } returns rs
    every { rs.next() } returns true
    every { rs.getInt(1) } returns 0
    every { conn.catalog } returns "mydb"

    beforeEach { clearMocks(jdbc) }

    // ── reseedGenerators ───────────────────────────

    test("reseedGenerators adjusts auto_increment column") {
        every { jdbc.querySingle(match { it.contains("auto_increment") }, any(), any()) } returns
            mapOf("column_name" to "id")
        every { jdbc.querySingle(match { it.contains("MAX") }, *anyVararg()) } returns
            mapOf("max_val" to 100L)
        every { jdbc.execute(match { it.contains("AUTO_INCREMENT") }) } returns 0

        val cols = listOf(ColumnDescriptor("id", nullable = false), ColumnDescriptor("name", nullable = true))
        val result = sync.reseedGenerators(conn, "users", cols, truncatePerformed = false)

        result shouldHaveSize 1
        result[0].column shouldBe "id"
        result[0].newValue shouldBe 101
        result[0].sequenceName shouldBe null

        verify { jdbc.execute(match { it.contains("AUTO_INCREMENT = 101") }) }
    }

    test("reseedGenerators returns empty when no auto_increment column") {
        every { jdbc.querySingle(match { it.contains("auto_increment") }, any(), any()) } returns null

        val cols = listOf(ColumnDescriptor("name", nullable = true))
        sync.reseedGenerators(conn, "users", cols, truncatePerformed = false).shouldBeEmpty()
    }

    test("reseedGenerators returns empty when column not in importedColumns and no truncate") {
        every { jdbc.querySingle(match { it.contains("auto_increment") }, any(), any()) } returns
            mapOf("column_name" to "id")

        val cols = listOf(ColumnDescriptor("name", nullable = true)) // 'id' not in list
        sync.reseedGenerators(conn, "users", cols, truncatePerformed = false).shouldBeEmpty()
    }

    test("reseedGenerators with truncate and null max resets to 1") {
        every { jdbc.querySingle(match { it.contains("auto_increment") }, any(), any()) } returns
            mapOf("column_name" to "id")
        every { jdbc.querySingle(match { it.contains("MAX") }, *anyVararg()) } returns
            mapOf("max_val" to null)
        every { jdbc.execute(match { it.contains("AUTO_INCREMENT") }) } returns 0

        val cols = listOf(ColumnDescriptor("id", nullable = false))
        val result = sync.reseedGenerators(conn, "users", cols, truncatePerformed = true)

        result shouldHaveSize 1
        result[0].newValue shouldBe 1
        verify { jdbc.execute(match { it.contains("AUTO_INCREMENT = 1") }) }
    }

    test("reseedGenerators with null max and no truncate returns empty") {
        every { jdbc.querySingle(match { it.contains("auto_increment") }, any(), any()) } returns
            mapOf("column_name" to "id")
        every { jdbc.querySingle(match { it.contains("MAX") }, *anyVararg()) } returns
            mapOf("max_val" to null)

        val cols = listOf(ColumnDescriptor("id", nullable = false))
        sync.reseedGenerators(conn, "users", cols, truncatePerformed = false).shouldBeEmpty()
    }

    test("interface overload forwards with truncatePerformed=false") {
        every { jdbc.querySingle(match { it.contains("auto_increment") }, any(), any()) } returns null

        val cols = listOf(ColumnDescriptor("id", nullable = false))
        // Uses the SchemaSync interface method (without truncatePerformed)
        (sync as dev.dmigrate.driver.data.SchemaSync).reseedGenerators(conn, "users", cols).shouldBeEmpty()
    }

    test("reseedGenerators with truncate and column not imported still reseeds") {
        every { jdbc.querySingle(match { it.contains("auto_increment") }, any(), any()) } returns
            mapOf("column_name" to "id")
        every { jdbc.querySingle(match { it.contains("MAX") }, *anyVararg()) } returns
            mapOf("max_val" to 50L)
        every { jdbc.execute(match { it.contains("AUTO_INCREMENT") }) } returns 0

        val cols = listOf(ColumnDescriptor("name", nullable = true)) // 'id' not imported but truncate was done
        val result = sync.reseedGenerators(conn, "users", cols, truncatePerformed = true)

        result shouldHaveSize 1
        result[0].newValue shouldBe 51
    }

    // ── trigger methods ────────────────────────────

    test("disableTriggers throws UnsupportedTriggerModeException") {
        shouldThrow<UnsupportedTriggerModeException> {
            sync.disableTriggers(conn, "users")
        }
    }

    test("assertNoUserTriggers throws UnsupportedTriggerModeException") {
        shouldThrow<UnsupportedTriggerModeException> {
            sync.assertNoUserTriggers(conn, "users")
        }
    }

    test("enableTriggers throws UnsupportedTriggerModeException") {
        shouldThrow<UnsupportedTriggerModeException> {
            sync.enableTriggers(conn, "users")
        }
    }
})
