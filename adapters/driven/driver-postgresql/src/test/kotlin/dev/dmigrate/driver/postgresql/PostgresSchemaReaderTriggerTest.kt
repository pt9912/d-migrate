package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldHaveSize as mapShouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/**
 * PostgreSQL schema-reader trigger reverse-read pins. Lives in its own
 * test class — like [PostgresSchemaReaderDependencyTest] — because folding
 * these into [PostgresSchemaReaderTest] would push that class past Detekt's
 * `LargeClass` threshold.
 *
 * Covers single-event reads, F4 multi-event aggregation (a
 * `BEFORE INSERT OR UPDATE` trigger arrives as one
 * `information_schema.triggers` row per event), and INSTEAD OF / STATEMENT /
 * WHEN-clause attributes.
 */
class PostgresSchemaReaderTriggerTest : FunSpec({

    // ── shared mocks (mirrors PostgresSchemaReaderTest) ─────

    val conn = mockk<Connection>(relaxUnitFun = true)
    val pool = mockk<ConnectionPool> {
        every { borrow() } returns conn
    }
    val jdbc = mockk<JdbcOperations>()
    val reader = PostgresSchemaReader(jdbcFactory = { jdbc })

    val stmt = mockk<Statement>(relaxUnitFun = true)
    val rs = mockk<ResultSet>(relaxUnitFun = true)
    every { conn.createStatement() } returns stmt
    every { stmt.executeQuery("SELECT current_schema()") } returns rs
    every { rs.next() } returns true
    every { rs.getString(1) } returns "public"
    every { conn.catalog } returns "testdb"

    fun stubEmptyDefaults() {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.sequences") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_enum") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("typtype = 'd'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("typtype = 'c'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_extension") }) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_get_viewdef") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("refobjsubid") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_depend") && it.contains("pg_proc") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("view_name") && it.contains("format_type") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_aggregate") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_trigger") && it.contains("tgfoid") }, any()) } returns emptyList()
        every {
            jdbc.queryList(match { it.contains("prosecdef") && it.contains("proconfig") }, any())
        } returns emptyList()
    }

    test("read includes triggers") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns listOf(
            mapOf("trigger_name" to "trg_audit", "event_object_table" to "users",
                "action_timing" to "AFTER", "event_manipulation" to "INSERT",
                "action_orientation" to "ROW", "action_condition" to null,
                "action_statement" to "EXECUTE FUNCTION audit_fn()"),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeFunctions = false, includeProcedures = false))

        result.schema.triggers.mapShouldHaveSize(1)
        val trigger = result.schema.triggers.values.first()
        trigger.table shouldBe "users"
        trigger.events shouldBe setOf(TriggerEvent.INSERT)
        trigger.timing shouldBe TriggerTiming.AFTER
        trigger.forEach shouldBe TriggerForEach.ROW
        trigger.sourceDialect shouldBe "postgresql"
    }

    test("read aggregates multi-event trigger rows into one events set (F4)") {
        stubEmptyDefaults()
        // information_schema.triggers surfaces one row per event, so a
        // `BEFORE INSERT OR UPDATE` trigger arrives as two rows sharing the
        // same (table, name). They must merge into a single trigger whose
        // events set holds both — the previous reader let the last row
        // overwrite the first and collapsed the trigger to a single event.
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns listOf(
            mapOf("trigger_name" to "film_fulltext_trigger", "event_object_table" to "film",
                "action_timing" to "BEFORE", "event_manipulation" to "INSERT",
                "action_orientation" to "ROW", "action_condition" to null,
                "action_statement" to "EXECUTE FUNCTION film_fulltext_update()"),
            mapOf("trigger_name" to "film_fulltext_trigger", "event_object_table" to "film",
                "action_timing" to "BEFORE", "event_manipulation" to "UPDATE",
                "action_orientation" to "ROW", "action_condition" to null,
                "action_statement" to "EXECUTE FUNCTION film_fulltext_update()"),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeFunctions = false, includeProcedures = false))

        result.schema.triggers.mapShouldHaveSize(1)
        val trigger = result.schema.triggers.values.single()
        trigger.events shouldBe setOf(TriggerEvent.INSERT, TriggerEvent.UPDATE)
        trigger.timing shouldBe TriggerTiming.BEFORE
        trigger.sourceDialect shouldBe "postgresql"
    }

    test("read trigger with DELETE event and INSTEAD OF timing") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns listOf(
            mapOf("trigger_name" to "trg_del", "event_object_table" to "v",
                "action_timing" to "INSTEAD OF", "event_manipulation" to "DELETE",
                "action_orientation" to "STATEMENT", "action_condition" to "OLD.id > 0",
                "action_statement" to "EXECUTE FUNCTION handle_del()"),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeFunctions = false, includeProcedures = false))

        val trigger = result.schema.triggers.values.first()
        trigger.events shouldBe setOf(TriggerEvent.DELETE)
        trigger.timing shouldBe TriggerTiming.INSTEAD_OF
        trigger.forEach shouldBe TriggerForEach.STATEMENT
        trigger.condition shouldBe "OLD.id > 0"
    }
})
