package dev.dmigrate.driver.postgresql

import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldHaveSize as mapShouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class PostgresMetadataQueriesTest : FunSpec({

    val jdbc = mockk<JdbcOperations>()

    // ── listTableRefs ──────────────────────────────

    test("listTableRefs maps rows to TableRef") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "public", "table_type" to "BASE TABLE"),
            mapOf("table_name" to "orders", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        val result = PostgresMetadataQueries.listTableRefs(jdbc, "public")
        result shouldHaveSize 2
        result[0].name shouldBe "users"
        result[0].schema shouldBe "public"
        result[0].type shouldBe "BASE TABLE"
        result[1].name shouldBe "orders"
    }

    test("listTableRefs with null table_type defaults to BASE TABLE") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "t1", "table_schema" to null, "table_type" to null),
        )
        val result = PostgresMetadataQueries.listTableRefs(jdbc, "public")
        result[0].type shouldBe "BASE TABLE"
        result[0].schema shouldBe null
    }

    // ── listColumns ────────────────────────────────

    test("listColumns returns raw column maps") {
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "integer", "is_nullable" to "NO"),
        )
        val result = PostgresMetadataQueries.listColumns(jdbc, "public", "users")
        result shouldHaveSize 1
        result[0]["column_name"] shouldBe "id"
    }

    // ── listPrimaryKeyColumns ──────────────────────

    test("listPrimaryKeyColumns returns column names") {
        every { jdbc.queryList(match { it.contains("PRIMARY KEY") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
            mapOf("column_name" to "tenant_id"),
        )
        val result = PostgresMetadataQueries.listPrimaryKeyColumns(jdbc, "public", "users")
        result shouldBe listOf("id", "tenant_id")
    }

    // ── listForeignKeys ────────────────────────────

    test("listForeignKeys maps pg_constraint rows with String array columns") {
        every { jdbc.queryList(match { it.contains("pg_constraint") }, any(), any()) } returns listOf(
            mapOf(
                "constraint_name" to "fk_order_user",
                "columns" to "{user_id}",
                "referenced_table" to "users",
                "referenced_columns" to "{id}",
                "confdeltype" to "c",
                "confupdtype" to "a",
            ),
        )
        val result = PostgresMetadataQueries.listForeignKeys(jdbc, "public", "orders")
        result shouldHaveSize 1
        result[0].name shouldBe "fk_order_user"
        result[0].columns shouldBe listOf("user_id")
        result[0].referencedTable shouldBe "users"
        result[0].referencedColumns shouldBe listOf("id")
        result[0].onDelete shouldBe "CASCADE"
        result[0].onUpdate shouldBe null // 'a' = NO ACTION
    }

    test("listForeignKeys maps all PG action codes") {
        every { jdbc.queryList(match { it.contains("pg_constraint") }, any(), any()) } returns listOf(
            mapOf(
                "constraint_name" to "fk1",
                "columns" to "{a}",
                "referenced_table" to "t",
                "referenced_columns" to "{b}",
                "confdeltype" to "n",
                "confupdtype" to "d",
            ),
            mapOf(
                "constraint_name" to "fk2",
                "columns" to "{c}",
                "referenced_table" to "t",
                "referenced_columns" to "{d}",
                "confdeltype" to "r",
                "confupdtype" to null,
            ),
        )
        val result = PostgresMetadataQueries.listForeignKeys(jdbc, "public", "x")
        result[0].onDelete shouldBe "SET NULL"
        result[0].onUpdate shouldBe "SET DEFAULT"
        result[1].onDelete shouldBe "RESTRICT"
        result[1].onUpdate shouldBe null
    }

    test("listForeignKeys handles java.sql.Array columns") {
        val sqlArray = mockk<java.sql.Array>()
        every { sqlArray.array } returns arrayOf("col_a", "col_b")
        val sqlArrayRef = mockk<java.sql.Array>()
        every { sqlArrayRef.array } returns arrayOf("ref_a", "ref_b")

        every { jdbc.queryList(match { it.contains("pg_constraint") }, any(), any()) } returns listOf(
            mapOf(
                "constraint_name" to "fk_composite",
                "columns" to sqlArray,
                "referenced_table" to "parent",
                "referenced_columns" to sqlArrayRef,
                "confdeltype" to "c",
                "confupdtype" to "c",
            ),
        )
        val result = PostgresMetadataQueries.listForeignKeys(jdbc, "public", "child")
        result[0].columns shouldBe listOf("col_a", "col_b")
        result[0].referencedColumns shouldBe listOf("ref_a", "ref_b")
    }

    // ── listUniqueConstraintColumns ────────────────

    test("listUniqueConstraintColumns groups by constraint name") {
        every { jdbc.queryList(match { it.contains("UNIQUE") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "uq_email", "column_name" to "email"),
            mapOf("constraint_name" to "uq_name", "column_name" to "first_name"),
            mapOf("constraint_name" to "uq_name", "column_name" to "last_name"),
        )
        val result = PostgresMetadataQueries.listUniqueConstraintColumns(jdbc, "public", "users")
        result.mapShouldHaveSize(2)
        result["uq_email"] shouldBe listOf("email")
        result["uq_name"] shouldBe listOf("first_name", "last_name")
    }

    // ── listCheckConstraints ───────────────────────

    test("listCheckConstraints maps to ConstraintProjection") {
        every { jdbc.queryList(match { it.contains("CHECK") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "chk_age", "check_clause" to "(age > 0)"),
        )
        val result = PostgresMetadataQueries.listCheckConstraints(jdbc, "public", "users")
        result shouldHaveSize 1
        result[0].name shouldBe "chk_age"
        result[0].type shouldBe "CHECK"
        result[0].expression shouldBe "(age > 0)"
    }

    // ── listIndices ────────────────────────────────

    test("listIndices maps rows with String array columns") {
        every { jdbc.queryList(match { it.contains("pg_index") }, any(), any()) } returns listOf(
            mapOf("index_name" to "idx_name", "columns" to "{name}", "is_unique" to false, "index_type" to "btree"),
            mapOf("index_name" to "idx_email", "columns" to "{email}", "is_unique" to true, "index_type" to "hash"),
        )
        val result = PostgresMetadataQueries.listIndices(jdbc, "public", "users")
        result shouldHaveSize 2
        result[0].name shouldBe "idx_name"
        result[0].columns shouldBe listOf("name")
        result[0].isUnique shouldBe false
        result[0].type shouldBe "btree"
        result[1].isUnique shouldBe true
        result[1].type shouldBe "hash"
    }

    test("listIndices handles java.sql.Array columns") {
        val sqlArray = mockk<java.sql.Array>()
        every { sqlArray.array } returns arrayOf("col_a", "col_b")

        every { jdbc.queryList(match { it.contains("pg_index") }, any(), any()) } returns listOf(
            mapOf("index_name" to "idx_comp", "columns" to sqlArray, "is_unique" to false, "index_type" to "btree"),
        )
        val result = PostgresMetadataQueries.listIndices(jdbc, "public", "t")
        result[0].columns shouldBe listOf("col_a", "col_b")
    }

    // ── listSequences ──────────────────────────────

    test("listSequences returns raw maps") {
        every { jdbc.queryList(match { it.contains("information_schema.sequences") }, any()) } returns listOf(
            mapOf("sequence_name" to "users_id_seq", "start_value" to "1", "increment" to "1",
                "minimum_value" to "1", "maximum_value" to "9223372036854775807",
                "cycle_option" to "NO", "cache_size" to 1L),
        )
        val result = PostgresMetadataQueries.listSequences(jdbc, "public")
        result shouldHaveSize 1
        result[0]["sequence_name"] shouldBe "users_id_seq"
    }

    // ── getPartitionInfo ───────────────────────────

    test("getPartitionInfo returns partition map") {
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns
            mapOf("partstrat" to "r", "key_columns" to "{created_at}")
        val result = PostgresMetadataQueries.getPartitionInfo(jdbc, "public", "events")
        result.shouldNotBeNull()
        result["partstrat"] shouldBe "r"
    }

    test("getPartitionInfo returns null for non-partitioned table") {
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns null
        PostgresMetadataQueries.getPartitionInfo(jdbc, "public", "users").shouldBeNull()
    }

    // ── listInstalledExtensions ─────────────────────

    test("listInstalledExtensions returns extension names") {
        every { jdbc.queryList(match { it.contains("pg_extension") }) } returns listOf(
            mapOf("extname" to "uuid-ossp"),
            mapOf("extname" to "pgcrypto"),
        )
        val result = PostgresMetadataQueries.listInstalledExtensions(jdbc)
        result shouldBe listOf("uuid-ossp", "pgcrypto")
    }

    // ── listEnumTypes ──────────────────────────────

    test("listEnumTypes groups labels by type name") {
        every { jdbc.queryList(match { it.contains("pg_enum") }, any()) } returns listOf(
            mapOf("typname" to "status", "enumlabel" to "active"),
            mapOf("typname" to "status", "enumlabel" to "inactive"),
            mapOf("typname" to "role", "enumlabel" to "admin"),
        )
        val result = PostgresMetadataQueries.listEnumTypes(jdbc, "public")
        result.mapShouldHaveSize(2)
        result["status"] shouldBe listOf("active", "inactive")
        result["role"] shouldBe listOf("admin")
    }

    // ── listDomainTypes ────────────────────────────

    test("listDomainTypes returns raw maps") {
        every { jdbc.queryList(match { it.contains("typtype = 'd'") }, any()) } returns listOf(
            mapOf("typname" to "email", "base_type" to "varchar", "numeric_precision" to null,
                "numeric_scale" to null, "domain_default" to null, "check_clause" to null),
        )
        val result = PostgresMetadataQueries.listDomainTypes(jdbc, "public")
        result shouldHaveSize 1
        result[0]["typname"] shouldBe "email"
    }

    // ── listCompositeTypes ─────────────────────────

    test("listCompositeTypes returns field rows") {
        every { jdbc.queryList(match { it.contains("typtype = 'c'") }, any()) } returns listOf(
            mapOf("typname" to "address", "attname" to "street", "attnum" to 1, "column_type" to "text"),
            mapOf("typname" to "address", "attname" to "city", "attnum" to 2, "column_type" to "text"),
        )
        val result = PostgresMetadataQueries.listCompositeTypes(jdbc, "public")
        result shouldHaveSize 2
    }

    // ── listViews ──────────────────────────────────

    test("listViews returns view maps") {
        every { jdbc.queryList(match { it.contains("information_schema.views") }, any()) } returns listOf(
            mapOf("table_name" to "active_users", "view_definition" to "SELECT * FROM users WHERE active"),
        )
        val result = PostgresMetadataQueries.listViews(jdbc, "public")
        result shouldHaveSize 1
        result[0]["table_name"] shouldBe "active_users"
    }

    // ── listViewFunctionDependencies ─────────────────

    test("listViewFunctionDependencies groups by view name") {
        every { jdbc.queryList(match { it.contains("pg_depend") }, any(), any()) } returns listOf(
            mapOf("view_name" to "v1", "function_name" to "fn_a"),
            mapOf("view_name" to "v1", "function_name" to "fn_b"),
            mapOf("view_name" to "v2", "function_name" to "fn_a"),
        )
        val result = PostgresMetadataQueries.listViewFunctionDependencies(jdbc, "public")
        result.keys shouldBe setOf("v1", "v2")
        result["v1"] shouldBe listOf("fn_a", "fn_b")
        result["v2"] shouldBe listOf("fn_a")
    }

    test("listViewFunctionDependencies returns empty map when no deps") {
        every { jdbc.queryList(match { it.contains("pg_depend") }, any(), any()) } returns emptyList()
        val result = PostgresMetadataQueries.listViewFunctionDependencies(jdbc, "public")
        result shouldBe emptyMap()
    }

    // ── listFunctions ──────────────────────────────

    test("listFunctions returns function maps") {
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf("routine_name" to "my_func", "specific_name" to "my_func_1234",
                "routine_type" to "FUNCTION", "data_type" to "integer",
                "type_udt_name" to "int4", "external_language" to "plpgsql",
                "routine_definition" to "BEGIN RETURN 1; END;", "is_deterministic" to "NO"),
        )
        val result = PostgresMetadataQueries.listFunctions(jdbc, "public")
        result shouldHaveSize 1
        result[0]["routine_name"] shouldBe "my_func"
    }

    // ── listProcedures ─────────────────────────────

    test("listProcedures returns procedure maps") {
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns listOf(
            mapOf("routine_name" to "my_proc", "specific_name" to "my_proc_5678",
                "routine_type" to "PROCEDURE", "external_language" to "plpgsql",
                "routine_definition" to "BEGIN END;"),
        )
        val result = PostgresMetadataQueries.listProcedures(jdbc, "public")
        result shouldHaveSize 1
    }

    // ── listRoutineParameters ──────────────────────

    test("listRoutineParameters returns parameter maps") {
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any()) } returns listOf(
            mapOf("parameter_name" to "p_id", "data_type" to "integer", "udt_name" to "int4",
                "parameter_mode" to "IN", "ordinal_position" to 1),
        )
        val result = PostgresMetadataQueries.listRoutineParameters(jdbc, "public", "my_func_1234")
        result shouldHaveSize 1
        result[0]["parameter_name"] shouldBe "p_id"
    }

    // ── listTriggers ───────────────────────────────

    test("listTriggers returns trigger maps") {
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns listOf(
            mapOf("trigger_name" to "trg_audit", "event_object_table" to "users",
                "action_timing" to "AFTER", "event_manipulation" to "INSERT",
                "action_orientation" to "ROW", "action_condition" to null,
                "action_statement" to "EXECUTE FUNCTION audit_fn()"),
        )
        val result = PostgresMetadataQueries.listTriggers(jdbc, "public")
        result shouldHaveSize 1
        result[0]["trigger_name"] shouldBe "trg_audit"
    }

    // ── empty results ──────────────────────────────

    test("listTableRefs returns empty list for empty schema") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns emptyList()
        PostgresMetadataQueries.listTableRefs(jdbc, "empty").shouldBeEmpty()
    }

    test("listForeignKeys handles unexpected column type as empty list") {
        every { jdbc.queryList(match { it.contains("pg_constraint") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "fk1", "columns" to 42,
                "referenced_table" to "t", "referenced_columns" to 42,
                "confdeltype" to "a", "confupdtype" to "a"),
        )
        val result = PostgresMetadataQueries.listForeignKeys(jdbc, "public", "x")
        result shouldHaveSize 1
        result[0].columns.shouldBeEmpty()
        result[0].referencedColumns.shouldBeEmpty()
    }

    test("listIndices handles unexpected column type as empty list") {
        every { jdbc.queryList(match { it.contains("pg_index") }, any(), any()) } returns listOf(
            mapOf("index_name" to "idx1", "columns" to 42, "is_unique" to false, "index_type" to "btree"),
        )
        val result = PostgresMetadataQueries.listIndices(jdbc, "public", "t")
        result shouldHaveSize 1
        result[0].columns.shouldBeEmpty()
    }

    test("listForeignKeys returns empty list when no FKs") {
        every { jdbc.queryList(match { it.contains("pg_constraint") }, any(), any()) } returns emptyList()
        PostgresMetadataQueries.listForeignKeys(jdbc, "public", "t").shouldBeEmpty()
    }
})
