package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.core.model.IndexSortDirection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldHaveSize as mapShouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot

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
        every { jdbc.queryList(match { it.contains("contype = 'p'") }, any(), any()) } returns listOf(
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
        every { jdbc.queryList(match { it.contains("contype = 'u'") }, any(), any()) } returns listOf(
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

    test("listIndices maps DESC indoption and normalizes ASC to null") {
        every { jdbc.queryList(match { it.contains("indoption") }, any(), any()) } returns listOf(
            mapOf(
                "index_name" to "idx_created",
                "columns" to "{created_at,id}",
                "directions" to "{DESC,NULL}",
                "is_unique" to false,
                "index_type" to "btree",
            ),
        )

        val result = PostgresMetadataQueries.listIndices(jdbc, "public", "orders")

        result[0].indexColumns shouldBe listOf(
            IndexColumn("created_at", IndexSortDirection.DESC),
            IndexColumn("id"),
        )
    }

    test("listIndices maps partial index predicate") {
        every { jdbc.queryList(match { it.contains("pg_get_expr") }, any(), any()) } returns listOf(
            mapOf(
                "index_name" to "uq_active_email",
                "columns" to "{email}",
                "directions" to "{NULL}",
                "is_unique" to true,
                "index_type" to "btree",
                "predicate" to "(deleted_at IS NULL)",
            ),
        )

        val result = PostgresMetadataQueries.listIndices(jdbc, "public", "users")

        result[0].where shouldBe "(deleted_at IS NULL)"
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
        val result = PostgresPartitionMetadataQueries.getPartitionInfo(jdbc, "public", "events")
        result.shouldNotBeNull()
        result["partstrat"] shouldBe "r"
    }

    test("getPartitionInfo returns null for non-partitioned table") {
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns null
        PostgresPartitionMetadataQueries.getPartitionInfo(jdbc, "public", "users").shouldBeNull()
    }

    // ── listPartitionChildren (AP1) ─────────────────

    test("listPartitionChildren returns name + raw bound clause per child") {
        every {
            jdbc.queryList(match { it.contains("pg_inherits") && it.contains("relpartbound") }, any(), any())
        } returns listOf(
            mapOf("partition_name" to "events_2022_01", "bound_expr" to "FOR VALUES FROM (0) TO (100)"),
            mapOf("partition_name" to "events_2022_02", "bound_expr" to "FOR VALUES FROM (100) TO (200)"),
        )
        val rows = PostgresPartitionMetadataQueries.listPartitionChildren(jdbc, "public", "events")
        rows shouldHaveSize 2
        rows[0]["partition_name"] shouldBe "events_2022_01"
        rows[0]["bound_expr"] shouldBe "FOR VALUES FROM (0) TO (100)"
    }

    test("listPartitionChildren restricts to declarative partitions via relispartition") {
        val captured = slot<String>()
        every { jdbc.queryList(capture(captured), any(), any()) } returns emptyList()
        PostgresPartitionMetadataQueries.listPartitionChildren(jdbc, "public", "events")
        captured.captured shouldContain "relispartition"
    }

    // ── listInheritedIndexNames (AP2a) ──────────────

    test("listInheritedIndexNames returns parent-propagated index names") {
        every {
            jdbc.queryList(match { it.contains("pg_inherits") && it.contains("cix.indexrelid") }, any(), any())
        } returns listOf(mapOf("index_name" to "events_parent_idx"))
        PostgresPartitionMetadataQueries.listInheritedIndexNames(jdbc, "public", "events_2022_01") shouldBe
            listOf("events_parent_idx")
    }

    // ── listTableRefs excludes partition children (AP2) ──

    test("listTableRefs query filters out relispartition children") {
        val captured = slot<String>()
        every { jdbc.queryList(capture(captured), any()) } returns emptyList()
        PostgresMetadataQueries.listTableRefs(jdbc, "public")
        captured.captured shouldContain "relispartition"
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
        every { jdbc.queryList(match { it.contains("pg_get_viewdef") }, any()) } returns listOf(
            mapOf(
                "table_name" to "active_users",
                "view_definition" to "SELECT * FROM users WHERE active",
                "is_materialized" to false,
            ),
        )
        val result = PostgresMetadataQueries.listViews(jdbc, "public")
        result shouldHaveSize 1
        result[0]["table_name"] shouldBe "active_users"
        result[0]["is_materialized"] shouldBe false
    }

    test("listViewRelationDependencies separates table, view and column dependencies") {
        every { jdbc.queryList(match { it.contains("refobjsubid") }, any(), any()) } returns listOf(
            mapOf(
                "view_name" to "v_orders",
                "relation_name" to "orders",
                "relation_kind" to "r",
                "column_name" to "id",
            ),
            mapOf(
                "view_name" to "v_orders",
                "relation_name" to "orders",
                "relation_kind" to "r",
                "column_name" to "status",
            ),
            mapOf(
                "view_name" to "v_summary",
                "relation_name" to "v_orders",
                "relation_kind" to "v",
                "column_name" to "id",
            ),
        )

        val result = PostgresMetadataQueries.listViewRelationDependencies(jdbc, "public")

        result["v_orders"]!!.tables shouldBe listOf("orders")
        result["v_orders"]!!.columns shouldBe mapOf("orders" to listOf("id", "status"))
        result["v_summary"]!!.views shouldBe listOf("v_orders")
    }

    test("listViewColumns returns visible signature in ordinal order") {
        every { jdbc.queryList(match { it.contains("view_name") && it.contains("format_type") }, any()) } returns listOf(
            mapOf(
                "view_name" to "v_orders",
                "column_name" to "id",
                "column_type" to "integer",
                "ordinal_position" to 1,
            ),
            mapOf(
                "view_name" to "v_orders",
                "column_name" to "status",
                "column_type" to "text",
                "ordinal_position" to 2,
            ),
        )

        val result = PostgresMetadataQueries.listViewColumns(jdbc, "public")

        result["v_orders"]!![0].name shouldBe "id"
        result["v_orders"]!![0].type shouldBe "integer"
        result["v_orders"]!![1].name shouldBe "status"
        result["v_orders"]!![1].type shouldBe "text"
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

    // ── listRoutineRelationDependencies (E.1 Slice D.2) ───────────

    test("listRoutineRelationDependencies splits relation kinds into tables/views/sequences per overload") {
        every {
            jdbc.queryList(
                match { it.contains("pg_proc") && it.contains("pg_depend") && it.contains("relkind") },
                any(), any(),
            )
        } returns listOf(
            mapOf("routine_name" to "fn", "routine_key" to "1001_fn", "routine_oid" to 1001L,
                "relation_name" to "orders", "relation_kind" to "r"),
            mapOf("routine_name" to "fn", "routine_key" to "1001_fn", "routine_oid" to 1001L,
                "relation_name" to "audit_view", "relation_kind" to "v"),
            mapOf("routine_name" to "fn", "routine_key" to "1001_fn", "routine_oid" to 1001L,
                "relation_name" to "audit_mat", "relation_kind" to "m"),
            mapOf("routine_name" to "fn", "routine_key" to "1001_fn", "routine_oid" to 1001L,
                "relation_name" to "order_seq", "relation_kind" to "S"),
            mapOf("routine_name" to "other_fn", "routine_key" to "2002_other_fn", "routine_oid" to 2002L,
                "relation_name" to "customers", "relation_kind" to "p"),
        )
        val result = PostgresProgrammabilityMetadataQueries.listRoutineRelationDependencies(jdbc, "public")
        val fnDeps = result[RoutineKey(name = "fn", oid = 1001L)]
        fnDeps?.tables shouldBe listOf("orders")
        fnDeps?.views shouldBe listOf("audit_view", "audit_mat")
        fnDeps?.sequences shouldBe listOf("order_seq")
        val otherDeps = result[RoutineKey(name = "other_fn", oid = 2002L)]
        otherDeps?.tables shouldBe listOf("customers")
        otherDeps?.sequences shouldBe emptyList<String>()
    }

    test("listRoutineRelationDependencies keeps overloads with same name distinct") {
        every {
            jdbc.queryList(
                match { it.contains("pg_proc") && it.contains("pg_depend") && it.contains("relkind") },
                any(), any(),
            )
        } returns listOf(
            mapOf("routine_name" to "fn", "routine_key" to "1001_fn", "routine_oid" to 1001L,
                "relation_name" to "orders", "relation_kind" to "r"),
            mapOf("routine_name" to "fn", "routine_key" to "1002_fn", "routine_oid" to 1002L,
                "relation_name" to "customers", "relation_kind" to "r"),
        )
        val result = PostgresProgrammabilityMetadataQueries.listRoutineRelationDependencies(jdbc, "public")
        result[RoutineKey(name = "fn", oid = 1001L)]?.tables shouldBe listOf("orders")
        result[RoutineKey(name = "fn", oid = 1002L)]?.tables shouldBe listOf("customers")
    }

    test("listRoutineRelationDependencies returns empty map when no deps") {
        every {
            jdbc.queryList(
                match { it.contains("pg_proc") && it.contains("pg_depend") && it.contains("relkind") },
                any(), any(),
            )
        } returns emptyList()
        PostgresProgrammabilityMetadataQueries.listRoutineRelationDependencies(jdbc, "public") shouldBe emptyMap()
    }

    // ── listTriggerFunctionDependencies (E.1 Slice D.2) ───────────

    test("listTriggerFunctionDependencies groups by (table, trigger_name)") {
        every { jdbc.queryList(match { it.contains("pg_trigger") && it.contains("tgfoid") }, any()) } returns listOf(
            mapOf("table_name" to "orders", "trigger_name" to "audit_t", "function_name" to "audit_fn"),
            mapOf("table_name" to "orders", "trigger_name" to "audit_t", "function_name" to "notify_fn"),
            mapOf("table_name" to "customers", "trigger_name" to "audit_t", "function_name" to "audit_fn"),
        )
        val result = PostgresProgrammabilityMetadataQueries.listTriggerFunctionDependencies(jdbc, "public")
        result[TriggerKey(table = "orders", name = "audit_t")] shouldBe listOf("audit_fn", "notify_fn")
        result[TriggerKey(table = "customers", name = "audit_t")] shouldBe listOf("audit_fn")
    }

    test("listTriggerFunctionDependencies returns empty map when no trigger rows") {
        every { jdbc.queryList(match { it.contains("pg_trigger") && it.contains("tgfoid") }, any()) } returns emptyList()
        PostgresProgrammabilityMetadataQueries.listTriggerFunctionDependencies(jdbc, "public") shouldBe emptyMap()
    }

    // ── listRoutineIdentityAttributes (E.1 Slice E) ───────────────

    test("listRoutineIdentityAttributes projects security/definer/searchPath per overload") {
        every {
            jdbc.queryList(
                match { it.contains("prosecdef") && it.contains("proconfig") && it.contains("pg_roles") },
                any(),
            )
        } returns listOf(
            mapOf(
                "routine_name" to "compute_total", "routine_oid" to 1001L,
                "security_definer" to true, "definer" to "svc_app",
                "config" to arrayOf("search_path=public,audit", "log_min_messages=warning"),
            ),
            mapOf(
                "routine_name" to "other_fn", "routine_oid" to 2002L,
                "security_definer" to false, "definer" to "postgres",
                "config" to null,
            ),
        )
        val result = PostgresProgrammabilityMetadataQueries.listRoutineIdentityAttributes(jdbc, "public")
        val secured = result[RoutineKey(name = "compute_total", oid = 1001L)]
        secured?.securityDefiner shouldBe true
        secured?.definer shouldBe "svc_app"
        secured?.searchPath shouldBe listOf("public", "audit")
        val other = result[RoutineKey(name = "other_fn", oid = 2002L)]
        other?.securityDefiner shouldBe false
        other?.searchPath shouldBe null
    }

    test("listRoutineIdentityAttributes handles a java.sql.Array config payload") {
        // Some JDBC drivers hand back text[] as java.sql.Array
        // rather than a Kotlin Array<String>; the parser must
        // accept both.
        val sqlArray = mockk<java.sql.Array>()
        every { sqlArray.array } returns arrayOf("search_path=public")
        every {
            jdbc.queryList(
                match { it.contains("prosecdef") && it.contains("proconfig") },
                any(),
            )
        } returns listOf(
            mapOf(
                "routine_name" to "fn", "routine_oid" to 5L,
                "security_definer" to false, "definer" to "app_role",
                "config" to sqlArray,
            ),
        )
        val result = PostgresProgrammabilityMetadataQueries.listRoutineIdentityAttributes(jdbc, "public")
        result[RoutineKey(name = "fn", oid = 5L)]?.searchPath shouldBe listOf("public")
    }

    test("listRoutineIdentityAttributes returns empty map when no rows") {
        every {
            jdbc.queryList(match { it.contains("prosecdef") && it.contains("proconfig") }, any())
        } returns emptyList()
        PostgresProgrammabilityMetadataQueries.listRoutineIdentityAttributes(jdbc, "public") shouldBe emptyMap()
    }

    test("listRoutineIdentityAttributes parses search_path with quoted comma-bearing segment") {
        // Slice E follow-up: PG `proconfig` quotes segments that
        // contain commas. The parser must split on un-quoted
        // commas only AND strip the surrounding double quotes so
        // the file-authored form (`weird,schema`) round-trips.
        every {
            jdbc.queryList(match { it.contains("prosecdef") && it.contains("proconfig") }, any())
        } returns listOf(
            mapOf(
                "routine_name" to "fn", "routine_oid" to 1L,
                "security_definer" to false, "definer" to null,
                "config" to arrayOf("search_path=\"weird,schema\",public"),
            ),
        )
        val result = PostgresProgrammabilityMetadataQueries.listRoutineIdentityAttributes(jdbc, "public")
        result[RoutineKey(name = "fn", oid = 1L)]?.searchPath shouldBe listOf("weird,schema", "public")
    }

    test("listRoutineIdentityAttributes collapses escaped double-quote inside a quoted segment") {
        // PG escapes a literal `"` inside a quoted segment as `""`.
        every {
            jdbc.queryList(match { it.contains("prosecdef") && it.contains("proconfig") }, any())
        } returns listOf(
            mapOf(
                "routine_name" to "fn", "routine_oid" to 2L,
                "security_definer" to false, "definer" to null,
                "config" to arrayOf("search_path=\"a\"\"b\",c"),
            ),
        )
        val result = PostgresProgrammabilityMetadataQueries.listRoutineIdentityAttributes(jdbc, "public")
        result[RoutineKey(name = "fn", oid = 2L)]?.searchPath shouldBe listOf("a\"b", "c")
    }

    test("listRoutineIdentityAttributes proconfig without search_path entry yields null search_path") {
        every {
            jdbc.queryList(match { it.contains("prosecdef") && it.contains("proconfig") }, any())
        } returns listOf(
            mapOf(
                "routine_name" to "fn", "routine_oid" to 3L,
                "security_definer" to false, "definer" to null,
                "config" to arrayOf("log_min_messages=warning", "statement_timeout=1000"),
            ),
        )
        val result = PostgresProgrammabilityMetadataQueries.listRoutineIdentityAttributes(jdbc, "public")
        result[RoutineKey(name = "fn", oid = 3L)]?.searchPath shouldBe null
    }

    test("listRoutineIdentityAttributes accepts a List<String> config payload") {
        // Some test-fake JDBC drivers hand back text[] as a Kotlin
        // List rather than an Array; toStringList must accept it.
        every {
            jdbc.queryList(match { it.contains("prosecdef") && it.contains("proconfig") }, any())
        } returns listOf(
            mapOf(
                "routine_name" to "fn", "routine_oid" to 4L,
                "security_definer" to false, "definer" to null,
                "config" to listOf("search_path=public"),
            ),
        )
        val result = PostgresProgrammabilityMetadataQueries.listRoutineIdentityAttributes(jdbc, "public")
        result[RoutineKey(name = "fn", oid = 4L)]?.searchPath shouldBe listOf("public")
    }
})
