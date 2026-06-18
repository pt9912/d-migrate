package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/**
 * E.1 Routine-Migration Slice D.2 follow-up: positive-projection
 * pins for the PostgreSQL schema reader's `DependencyInfo` wiring.
 * Lives in its own test class because adding the cases to
 * `PostgresSchemaReaderTest` would push it past Detekt's
 * `LargeClass` threshold.
 */
class PostgresSchemaReaderDependencyTest : FunSpec({

    val conn = mockk<Connection>(relaxUnitFun = true)
    val pool = mockk<ConnectionPool> {
        every { borrow() } returns conn
    }
    val jdbc = mockk<JdbcOperations>(relaxUnitFun = true)
    val stmt = mockk<Statement>(relaxUnitFun = true)
    val rs = mockk<ResultSet>(relaxUnitFun = true)
    every { conn.createStatement() } returns stmt
    every { stmt.executeQuery("SELECT current_schema()") } returns rs
    every { rs.next() } returns true
    every { rs.getString(1) } returns "public"
    every { conn.catalog } returns "testdb"

    fun stubReaderDefaults() {
        // Empty defaults for everything the schema reader probes by
        // default. The two tests below override only the rows they
        // actually care about.
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.sequences") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_enum") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("typtype = 'd'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("typtype = 'c'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_extension") }) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_get_viewdef") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("refobjsubid") }, any(), any()) } returns emptyList()
        every {
            jdbc.queryList(match { it.contains("pg_depend") && it.contains("view_name") }, any(), any())
        } returns emptyList()
        every {
            jdbc.queryList(match { it.contains("routine_name") && it.contains("routine_oid") && it.contains("pg_depend") }, any(), any())
        } returns emptyList()
        // E.1 Slice E: identity-attribute projection from pg_proc.
        every {
            jdbc.queryList(match { it.contains("prosecdef") && it.contains("proconfig") }, any())
        } returns emptyList()
        every { jdbc.queryList(match { it.contains("view_name") && it.contains("format_type") }, any()) } returns
            emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns emptyList()
        // N7: user-defined aggregates from pg_aggregate.
        every { jdbc.queryList(match { it.contains("pg_aggregate") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns emptyList()
        every {
            jdbc.queryList(match { it.contains("pg_trigger") && it.contains("tgfoid") }, any())
        } returns emptyList()
    }

    test("read attaches DependencyInfo to a function via pg_depend projection") {
        stubReaderDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf(
                "routine_name" to "compute_total",
                "specific_name" to "compute_total_1001",
                "routine_type" to "FUNCTION",
                "data_type" to "integer",
                "type_udt_name" to "int4",
                "external_language" to "plpgsql",
                "routine_definition" to "BEGIN RETURN 1; END",
                "is_deterministic" to "YES",
            ),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any()) } returns
            emptyList()
        every {
            jdbc.queryList(
                match { it.contains("routine_name") && it.contains("routine_oid") && it.contains("pg_depend") },
                any(), any(),
            )
        } returns listOf(
            mapOf(
                "routine_name" to "compute_total", "routine_key" to "1001_compute_total",
                "routine_oid" to 1001L, "relation_name" to "orders", "relation_kind" to "r",
            ),
            mapOf(
                "routine_name" to "compute_total", "routine_key" to "1001_compute_total",
                "routine_oid" to 1001L, "relation_name" to "order_seq", "relation_kind" to "S",
            ),
        )

        val reader = PostgresSchemaReader(jdbcFactory = { jdbc })
        val result = reader.read(pool, SchemaReadOptions())
        val fn = result.schema.functions.values.single()
        fn.dependencies.shouldNotBeNull()
        fn.dependencies!!.tables shouldBe listOf("orders")
        fn.dependencies!!.sequences shouldBe listOf("order_seq")
        fn.dependencies!!.views.shouldBeEmpty()
    }

    test("read attaches DependencyInfo.functions to a trigger via pg_trigger.tgfoid projection") {
        stubReaderDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns listOf(
            mapOf(
                "trigger_name" to "audit_orders_t",
                "event_object_table" to "orders",
                "action_timing" to "BEFORE",
                "event_manipulation" to "INSERT",
                "action_orientation" to "ROW",
                "action_condition" to null,
                "action_statement" to "EXECUTE FUNCTION audit_fn()",
            ),
        )
        every { jdbc.queryList(match { it.contains("pg_trigger") && it.contains("tgfoid") }, any()) } returns
            listOf(
                mapOf(
                    "table_name" to "orders", "trigger_name" to "audit_orders_t",
                    "function_name" to "audit_fn",
                ),
            )

        val reader = PostgresSchemaReader(jdbcFactory = { jdbc })
        val result = reader.read(pool, SchemaReadOptions())
        val trigger = result.schema.triggers.values.single()
        trigger.dependencies.shouldNotBeNull()
        trigger.dependencies!!.functions shouldBe listOf("audit_fn")
    }

    test("Slice E: read populates security / definer / searchPath from pg_proc") {
        stubReaderDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf(
                "routine_name" to "secured_fn",
                "specific_name" to "secured_fn_1234",
                "routine_type" to "FUNCTION",
                "data_type" to "integer",
                "type_udt_name" to "int4",
                "external_language" to "plpgsql",
                "routine_definition" to "BEGIN RETURN 1; END",
                "is_deterministic" to "YES",
            ),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any()) } returns
            emptyList()
        every {
            jdbc.queryList(match { it.contains("prosecdef") && it.contains("proconfig") }, any())
        } returns listOf(
            mapOf(
                "routine_name" to "secured_fn", "routine_oid" to 1234L,
                "security_definer" to true, "definer" to "svc_app",
                "config" to arrayOf("search_path=public,audit"),
            ),
        )

        val reader = PostgresSchemaReader(jdbcFactory = { jdbc })
        val result = reader.read(pool, SchemaReadOptions())
        val fn = result.schema.functions.values.single()
        fn.security shouldBe dev.dmigrate.core.model.RoutineSecurity.DEFINER
        fn.definer shouldBe "svc_app"
        fn.searchPath shouldBe listOf("public", "audit")
    }

    test("Slice E: INVOKER routine without search_path leaves definer / searchPath null") {
        stubReaderDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf(
                "routine_name" to "plain_fn",
                "specific_name" to "plain_fn_5678",
                "routine_type" to "FUNCTION",
                "data_type" to "integer",
                "type_udt_name" to "int4",
                "external_language" to "plpgsql",
                "routine_definition" to "BEGIN RETURN 1; END",
                "is_deterministic" to "YES",
            ),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any()) } returns
            emptyList()
        every {
            jdbc.queryList(match { it.contains("prosecdef") && it.contains("proconfig") }, any())
        } returns listOf(
            mapOf(
                "routine_name" to "plain_fn", "routine_oid" to 5678L,
                "security_definer" to false, "definer" to "postgres",
                "config" to null,
            ),
        )

        val reader = PostgresSchemaReader(jdbcFactory = { jdbc })
        val result = reader.read(pool, SchemaReadOptions())
        val fn = result.schema.functions.values.single()
        fn.security shouldBe dev.dmigrate.core.model.RoutineSecurity.INVOKER
        // INVOKER routines suppress the definer field — the role
        // that owns the routine is irrelevant when execution runs
        // as the calling role.
        fn.definer shouldBe null
        fn.searchPath shouldBe null
    }
})
