package dev.dmigrate.driver.mssql

import dev.dmigrate.core.data.ColumnDescriptor
import dev.dmigrate.driver.data.SequenceAdjustment
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.sql.Connection

class MssqlSchemaSyncTest : FunSpec({

    fun rig(
        identityColumn: String? = "id",
        maxValue: Long? = 42L,
        seed: Long = 1L,
        increment: Long = 1L,
        lastValue: Long? = 7L,
    ): Pair<MssqlSchemaSync, JdbcOperations> {
        // `execute` liefert Int (kein Unit) — relaxUnitFun genuegt nicht.
        val jdbc = mockk<JdbcOperations>(relaxed = true)
        every { jdbc.querySingle(match { it.contains("SCHEMA_NAME()") }) } returns mapOf("schema_name" to "dbo")
        every { jdbc.querySingle(match { it.contains("sys.identity_columns") }, any()) } returns
            identityColumn?.let {
                mapOf(
                    "column_name" to it, "seed_value" to seed,
                    "increment_value" to increment, "last_value" to lastValue,
                )
            }
        every { jdbc.querySingle(match { it.contains("MAX(") }) } returns
            maxValue?.let { mapOf("max_value" to it) } ?: mapOf("max_value" to null)
        return MssqlSchemaSync { jdbc } to jdbc
    }

    fun column(name: String) = ColumnDescriptor(name = name, nullable = false)

    test("reseeds the identity column to the highest imported value") {
        val (sync, jdbc) = rig()
        val adjustments = sync.reseedGenerators(
            conn = mockk<Connection>(), table = "orders",
            importedColumns = listOf(column("id"), column("name")), truncatePerformed = false,
        )
        adjustments shouldBe listOf(SequenceAdjustment("orders", "id", null, 43L))
        verify { jdbc.execute("DBCC CHECKIDENT ('[dbo].[orders]', RESEED, 42)") }
    }

    test("seed and increment come from the catalog, not assumed as 1/1") {
        val (sync, jdbc) = rig(maxValue = 100L, seed = 1000L, increment = 10L)
        sync.reseedGenerators(mockk<Connection>(), "orders", listOf(column("id")), false) shouldBe
            listOf(SequenceAdjustment("orders", "id", null, 110L))
        verify { jdbc.execute("DBCC CHECKIDENT ('[dbo].[orders]', RESEED, 100)") }
    }

    test("a table that never held a row reseeds to the seed verbatim") {
        // sys.identity_columns.last_value = null → SQL Server nimmt den RESEED-Wert
        // als ERSTEN Wert; ein `seed - increment` ergaebe hier 0 statt 1.
        val (sync, jdbc) = rig(maxValue = null, seed = 1L, increment = 1L, lastValue = null)
        sync.reseedGenerators(mockk<Connection>(), "orders", emptyList(), truncatePerformed = true) shouldBe
            listOf(SequenceAdjustment("orders", "id", null, 1L))
        verify { jdbc.execute("DBCC CHECKIDENT ('[dbo].[orders]', RESEED, 1)") }
    }

    test("truncated table resets to the declared IDENTITY seed") {
        val (sync, jdbc) = rig(maxValue = null, seed = 1000L, increment = 10L)
        sync.reseedGenerators(mockk<Connection>(), "orders", emptyList(), truncatePerformed = true) shouldBe
            listOf(SequenceAdjustment("orders", "id", null, 1000L))
        verify { jdbc.execute("DBCC CHECKIDENT ('[dbo].[orders]', RESEED, 990)") }
    }

    test("no identity column means no adjustment") {
        val (sync, _) = rig(identityColumn = null)
        sync.reseedGenerators(mockk<Connection>(), "orders", listOf(column("id")), false).shouldBeEmpty()
    }

    test("identity column not imported and no truncate is a no-op") {
        val (sync, _) = rig()
        sync.reseedGenerators(mockk<Connection>(), "orders", listOf(column("name")), false).shouldBeEmpty()
    }

    test("empty table after truncate resets to the start value") {
        val (sync, jdbc) = rig(maxValue = null)
        val adjustments = sync.reseedGenerators(mockk<Connection>(), "orders", emptyList(), truncatePerformed = true)
        adjustments shouldBe listOf(SequenceAdjustment("orders", "id", null, 1L))
        verify { jdbc.execute("DBCC CHECKIDENT ('[dbo].[orders]', RESEED, 0)") }
    }

    test("schema-qualified tables keep their schema in the DBCC literal") {
        val (sync, jdbc) = rig()
        sync.reseedGenerators(mockk<Connection>(), "sales.orders", listOf(column("id")), false)
        verify { jdbc.execute("DBCC CHECKIDENT ('[sales].[orders]', RESEED, 42)") }
    }
})
