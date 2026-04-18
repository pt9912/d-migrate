package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk

class MysqlMetadataQueriesTest : FunSpec({

    val jdbc = mockk<JdbcOperations>()

    // ── listTableRefs ──────────────────────────────

    test("listTableRefs maps rows to TableRef") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
            mapOf("table_name" to "orders", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        val result = MysqlMetadataQueries.listTableRefs(jdbc, "mydb")
        result shouldHaveSize 2
        result[0].name shouldBe "users"
        result[0].schema shouldBe "mydb"
        result[1].name shouldBe "orders"
    }

    test("listTableRefs defaults null table_type to BASE TABLE") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "t", "table_schema" to null, "table_type" to null),
        )
        val result = MysqlMetadataQueries.listTableRefs(jdbc, "db")
        result[0].type shouldBe "BASE TABLE"
    }

    // ── listTableEngine ────────────────────────────

    test("listTableEngine returns engine name") {
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns
            mapOf("engine" to "InnoDB")
        MysqlMetadataQueries.listTableEngine(jdbc, "mydb", "users") shouldBe "InnoDB"
    }

    test("listTableEngine returns null when no row") {
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns null
        MysqlMetadataQueries.listTableEngine(jdbc, "mydb", "missing").shouldBeNull()
    }

    // ── listColumns ────────────────────────────────

    test("listColumns returns raw column maps") {
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
        )
        val result = MysqlMetadataQueries.listColumns(jdbc, "mydb", "users")
        result shouldHaveSize 1
        result[0]["column_name"] shouldBe "id"
    }

    // ── listPrimaryKeyColumns ──────────────────────

    test("listPrimaryKeyColumns returns column names") {
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        val result = MysqlMetadataQueries.listPrimaryKeyColumns(jdbc, "mydb", "users")
        result shouldBe listOf("id")
    }

    // ── listForeignKeys ────────────────────────────

    test("listForeignKeys groups by constraint name") {
        every { jdbc.queryList(match { it.contains("referential_constraints") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "fk_order_user", "column_name" to "user_id",
                "referenced_table_name" to "users", "referenced_column_name" to "id",
                "delete_rule" to "CASCADE", "update_rule" to "NO ACTION"),
        )
        val result = MysqlMetadataQueries.listForeignKeys(jdbc, "mydb", "orders")
        result shouldHaveSize 1
        result[0].name shouldBe "fk_order_user"
        result[0].columns shouldBe listOf("user_id")
        result[0].referencedTable shouldBe "users"
        result[0].referencedColumns shouldBe listOf("id")
        result[0].onDelete shouldBe "CASCADE"
        result[0].onUpdate shouldBe null // NO ACTION is filtered
    }

    test("listForeignKeys filters RESTRICT as null") {
        every { jdbc.queryList(match { it.contains("referential_constraints") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "fk1", "column_name" to "a",
                "referenced_table_name" to "t", "referenced_column_name" to "b",
                "delete_rule" to "RESTRICT", "update_rule" to "SET NULL"),
        )
        val result = MysqlMetadataQueries.listForeignKeys(jdbc, "mydb", "x")
        result[0].onDelete shouldBe null
        result[0].onUpdate shouldBe "SET NULL"
    }

    test("listForeignKeys handles composite FK") {
        every { jdbc.queryList(match { it.contains("referential_constraints") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "fk_comp", "column_name" to "a",
                "referenced_table_name" to "parent", "referenced_column_name" to "pa",
                "delete_rule" to "CASCADE", "update_rule" to "CASCADE"),
            mapOf("constraint_name" to "fk_comp", "column_name" to "b",
                "referenced_table_name" to "parent", "referenced_column_name" to "pb",
                "delete_rule" to "CASCADE", "update_rule" to "CASCADE"),
        )
        val result = MysqlMetadataQueries.listForeignKeys(jdbc, "mydb", "child")
        result shouldHaveSize 1
        result[0].columns shouldBe listOf("a", "b")
        result[0].referencedColumns shouldBe listOf("pa", "pb")
    }

    // ── listCheckConstraints ───────────────────────

    test("listCheckConstraints maps to ConstraintProjection") {
        every { jdbc.queryList(match { it.contains("CHECK") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "chk_age", "check_clause" to "(age > 0)"),
        )
        val result = MysqlMetadataQueries.listCheckConstraints(jdbc, "mydb", "users")
        result shouldHaveSize 1
        result[0].name shouldBe "chk_age"
        result[0].type shouldBe "CHECK"
        result[0].expression shouldBe "(age > 0)"
    }

    // ── listIndices ────────────────────────────────

    test("listIndices groups by index name") {
        every { jdbc.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns listOf(
            mapOf("index_name" to "idx_name", "column_name" to "first_name",
                "non_unique" to 1, "seq_in_index" to 1, "index_type" to "BTREE"),
            mapOf("index_name" to "idx_name", "column_name" to "last_name",
                "non_unique" to 1, "seq_in_index" to 2, "index_type" to "BTREE"),
            mapOf("index_name" to "idx_email", "column_name" to "email",
                "non_unique" to 0, "seq_in_index" to 1, "index_type" to "BTREE"),
        )
        val result = MysqlMetadataQueries.listIndices(jdbc, "mydb", "users")
        result shouldHaveSize 2
        val nameIdx = result.find { it.name == "idx_name" }!!
        nameIdx.columns shouldBe listOf("first_name", "last_name")
        nameIdx.isUnique shouldBe false
        val emailIdx = result.find { it.name == "idx_email" }!!
        emailIdx.isUnique shouldBe true
    }

    // ── listViews ──────────────────────────────────

    test("listViews returns view maps") {
        every { jdbc.queryList(match { it.contains("information_schema.views") }, any()) } returns listOf(
            mapOf("table_name" to "active_users", "view_definition" to "SELECT * FROM users WHERE active = 1"),
        )
        val result = MysqlMetadataQueries.listViews(jdbc, "mydb")
        result shouldHaveSize 1
        result[0]["table_name"] shouldBe "active_users"
    }

    // ── listFunctions ──────────────────────────────

    test("listFunctions returns function maps") {
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf("routine_name" to "my_func", "routine_type" to "FUNCTION",
                "data_type" to "int", "dtd_identifier" to "int",
                "routine_definition" to "RETURN 1;", "is_deterministic" to "YES",
                "routine_body" to "SQL"),
        )
        val result = MysqlMetadataQueries.listFunctions(jdbc, "mydb")
        result shouldHaveSize 1
        result[0]["routine_name"] shouldBe "my_func"
    }

    // ── listProcedures ─────────────────────────────

    test("listProcedures returns procedure maps") {
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns listOf(
            mapOf("routine_name" to "my_proc", "routine_type" to "PROCEDURE",
                "routine_definition" to "BEGIN END;", "routine_body" to "SQL"),
        )
        val result = MysqlMetadataQueries.listProcedures(jdbc, "mydb")
        result shouldHaveSize 1
    }

    // ── listRoutineParameters ──────────────────────

    test("listRoutineParameters returns parameter maps") {
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any(), any()) } returns listOf(
            mapOf("parameter_name" to "p_id", "data_type" to "int", "dtd_identifier" to "int",
                "parameter_mode" to "IN", "ordinal_position" to 1),
        )
        val result = MysqlMetadataQueries.listRoutineParameters(jdbc, "mydb", "my_func", "FUNCTION")
        result shouldHaveSize 1
        result[0]["parameter_name"] shouldBe "p_id"
    }

    // ── listTriggers ───────────────────────────────

    test("listTriggers returns trigger maps") {
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns listOf(
            mapOf("trigger_name" to "trg_audit", "event_object_table" to "users",
                "action_timing" to "AFTER", "event_manipulation" to "INSERT",
                "action_orientation" to "ROW", "action_statement" to "INSERT INTO audit VALUES (NEW.id)"),
        )
        val result = MysqlMetadataQueries.listTriggers(jdbc, "mydb")
        result shouldHaveSize 1
        result[0]["trigger_name"] shouldBe "trg_audit"
    }

    // ── listForeignKeys edge cases ─────────────────

    test("listForeignKeys returns empty list when no FKs") {
        every { jdbc.queryList(match { it.contains("referential_constraints") }, any(), any()) } returns emptyList()
        MysqlMetadataQueries.listForeignKeys(jdbc, "mydb", "t").shouldBeEmpty()
    }

    test("listCheckConstraints returns empty list when no checks") {
        every { jdbc.queryList(match { it.contains("CHECK") }, any(), any()) } returns emptyList()
        MysqlMetadataQueries.listCheckConstraints(jdbc, "mydb", "t").shouldBeEmpty()
    }

    test("listIndices returns empty list when no indices") {
        every { jdbc.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns emptyList()
        MysqlMetadataQueries.listIndices(jdbc, "mydb", "t").shouldBeEmpty()
    }

    // ── empty results ──────────────────────────────

    test("listTableRefs returns empty list for empty database") {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns emptyList()
        MysqlMetadataQueries.listTableRefs(jdbc, "empty").shouldBeEmpty()
    }
})
