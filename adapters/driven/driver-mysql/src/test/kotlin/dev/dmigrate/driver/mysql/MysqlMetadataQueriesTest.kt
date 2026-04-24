package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.metadata.JdbcOperations
import dev.dmigrate.driver.SqlIdentifiers
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

    test("listCheckConstraints scopes the query to schema and table without leaking OR precedence") {
        var capturedSql: String? = null
        every { jdbc.queryList(match { it.contains("FROM information_schema.table_constraints") }, any(), any()) } answers {
            capturedSql = firstArg()
            emptyList()
        }

        MysqlMetadataQueries.listCheckConstraints(jdbc, "mydb", "users").shouldBeEmpty()

        capturedSql shouldBe """
            SELECT tc.constraint_name, cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON tc.constraint_name = cc.constraint_name
              AND tc.constraint_schema = cc.constraint_schema
            WHERE tc.constraint_type = 'CHECK'
              AND tc.table_schema = ? AND tc.table_name = ?
            ORDER BY tc.constraint_name
        """.trimIndent()
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

    // ── listViewRoutineUsage ──────────────────────────

    test("listViewRoutineUsage groups by view name") {
        every { jdbc.queryList(match { it.contains("VIEW_ROUTINE_USAGE") }, any()) } returns listOf(
            mapOf("view_name" to "v1", "routine_name" to "fn_a"),
            mapOf("view_name" to "v1", "routine_name" to "fn_b"),
        )
        val result = MysqlMetadataQueries.listViewRoutineUsage(jdbc, "mydb")
        result.keys shouldBe setOf("v1")
        result["v1"] shouldBe listOf("fn_a", "fn_b")
    }

    test("listViewRoutineUsage returns empty map on older MySQL without VIEW_ROUTINE_USAGE") {
        every { jdbc.queryList(match { it.contains("VIEW_ROUTINE_USAGE") }, any()) } throws
            RuntimeException("Table 'VIEW_ROUTINE_USAGE' doesn't exist")
        val result = MysqlMetadataQueries.listViewRoutineUsage(jdbc, "mydb")
        result shouldBe emptyMap()
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

    // ── Sequence-support queries (0.9.4 AP 6.1) ──────────

    test("checkSupportTableExists returns true when dmg_sequences is present") {
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns
            mapOf("table_name" to "dmg_sequences")
        MysqlMetadataQueries.checkSupportTableExists(jdbc, "mydb") shouldBe true
    }

    test("checkSupportTableExists returns false when dmg_sequences is absent") {
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns null
        MysqlMetadataQueries.checkSupportTableExists(jdbc, "mydb") shouldBe false
    }

    test("checkSupportTableExists returns null on permission error") {
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } throws
            RuntimeException("access denied")
        MysqlMetadataQueries.checkSupportTableExists(jdbc, "mydb").shouldBeNull()
    }

    test("checkSupportTableShape returns true when all columns have correct types") {
        every { jdbc.queryList(match { "information_schema.columns" in it && "table_name = ?" in it }, any(), any()) } returns listOf(
            mapOf("column_name" to "managed_by", "data_type" to "varchar"),
            mapOf("column_name" to "format_version", "data_type" to "varchar"),
            mapOf("column_name" to "name", "data_type" to "varchar"),
            mapOf("column_name" to "next_value", "data_type" to "bigint"),
            mapOf("column_name" to "increment_by", "data_type" to "bigint"),
            mapOf("column_name" to "min_value", "data_type" to "bigint"),
            mapOf("column_name" to "max_value", "data_type" to "bigint"),
            mapOf("column_name" to "cycle_enabled", "data_type" to "tinyint"),
            mapOf("column_name" to "cache_size", "data_type" to "int"),
        )
        MysqlMetadataQueries.checkSupportTableShape(jdbc, "mydb") shouldBe true
    }

    test("checkSupportTableShape returns false when columns are missing") {
        every { jdbc.queryList(match { "information_schema.columns" in it && "table_name = ?" in it }, any(), any()) } returns listOf(
            mapOf("column_name" to "name", "data_type" to "varchar"),
            mapOf("column_name" to "value", "data_type" to "text"),
        )
        MysqlMetadataQueries.checkSupportTableShape(jdbc, "mydb") shouldBe false
    }

    test("checkSupportTableShape returns true when extra columns present alongside required columns") {
        every { jdbc.queryList(match { "information_schema.columns" in it && "table_name = ?" in it }, any(), any()) } returns listOf(
            mapOf("column_name" to "managed_by", "data_type" to "varchar"),
            mapOf("column_name" to "format_version", "data_type" to "varchar"),
            mapOf("column_name" to "name", "data_type" to "varchar"),
            mapOf("column_name" to "next_value", "data_type" to "bigint"),
            mapOf("column_name" to "increment_by", "data_type" to "bigint"),
            mapOf("column_name" to "min_value", "data_type" to "bigint"),
            mapOf("column_name" to "max_value", "data_type" to "bigint"),
            mapOf("column_name" to "cycle_enabled", "data_type" to "tinyint"),
            mapOf("column_name" to "cache_size", "data_type" to "int"),
            // Extra non-canonical columns — must not break shape validation
            mapOf("column_name" to "description", "data_type" to "varchar"),
            mapOf("column_name" to "created_at", "data_type" to "timestamp"),
        )
        MysqlMetadataQueries.checkSupportTableShape(jdbc, "mydb") shouldBe true
    }

    test("checkSupportTableShape returns false when column type is wrong") {
        every { jdbc.queryList(match { "information_schema.columns" in it && "table_name = ?" in it }, any(), any()) } returns listOf(
            mapOf("column_name" to "managed_by", "data_type" to "varchar"),
            mapOf("column_name" to "format_version", "data_type" to "varchar"),
            mapOf("column_name" to "name", "data_type" to "varchar"),
            mapOf("column_name" to "next_value", "data_type" to "varchar"),  // wrong type!
            mapOf("column_name" to "increment_by", "data_type" to "bigint"),
            mapOf("column_name" to "min_value", "data_type" to "bigint"),
            mapOf("column_name" to "max_value", "data_type" to "bigint"),
            mapOf("column_name" to "cycle_enabled", "data_type" to "tinyint"),
            mapOf("column_name" to "cache_size", "data_type" to "int"),
        )
        MysqlMetadataQueries.checkSupportTableShape(jdbc, "mydb") shouldBe false
    }

    test("lookupSupportRoutine returns CONFIRMED when marker and signature match") {
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns
            mapOf("routine_name" to "dmg_nextval",
                "routine_definition" to "/* d-migrate:mysql-sequence-v1 object=nextval */ BEGIN END",
                "data_type" to "bigint", "dtd_identifier" to "bigint")
        every { jdbc.queryList(match { "information_schema.parameters" in it }, any(), any()) } returns listOf(
            mapOf("ordinal_position" to 1),
        )
        MysqlMetadataQueries.lookupSupportRoutine(jdbc, "mydb", "dmg_nextval") shouldBe SupportRoutineState.CONFIRMED
    }

    test("lookupSupportRoutine returns MISSING when routine does not exist") {
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns null
        MysqlMetadataQueries.lookupSupportRoutine(jdbc, "mydb", "dmg_nextval") shouldBe SupportRoutineState.MISSING
    }

    test("lookupSupportRoutine returns NON_CANONICAL when marker is absent (user object)") {
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns
            mapOf("routine_name" to "dmg_nextval", "routine_definition" to "BEGIN RETURN 1; END",
                "data_type" to "bigint", "dtd_identifier" to "bigint")
        MysqlMetadataQueries.lookupSupportRoutine(jdbc, "mydb", "dmg_nextval") shouldBe SupportRoutineState.NON_CANONICAL
    }

    test("lookupSupportRoutine returns NON_CANONICAL when return type does not match") {
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns
            mapOf("routine_name" to "dmg_nextval",
                "routine_definition" to "/* d-migrate:mysql-sequence-v1 object=nextval */ BEGIN END",
                "data_type" to "varchar", "dtd_identifier" to "varchar(255)")
        MysqlMetadataQueries.lookupSupportRoutine(jdbc, "mydb", "dmg_nextval") shouldBe SupportRoutineState.NON_CANONICAL
    }

    test("lookupSupportRoutine returns NOT_ACCESSIBLE on permission error") {
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } throws
            RuntimeException("access denied")
        MysqlMetadataQueries.lookupSupportRoutine(jdbc, "mydb", "dmg_nextval") shouldBe SupportRoutineState.NOT_ACCESSIBLE
    }

    test("listPotentialSupportTriggers returns CONFIRMED for valid trigger") {
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns listOf(
            mapOf("trigger_name" to "dmg_seq_orders_invoice_number_7b0a7b2f55_bi",
                "action_timing" to "BEFORE", "event_manipulation" to "INSERT",
                "event_object_table" to "orders",
                "action_statement" to (
                    "/* d-migrate:mysql-sequence-v1 object=sequence-trigger */ " +
                        "IF NEW.invoice_number IS NULL THEN " +
                        "SET NEW.invoice_number = dmg_nextval('invoice_seq'); END IF;"
                    ),
            ),
        )
        val result = MysqlMetadataQueries.listPotentialSupportTriggers(jdbc, "mydb")
        result.accessible shouldBe true
        result.triggers shouldHaveSize 1
        result.triggers[0].state shouldBe SupportTriggerState.CONFIRMED
        result.triggers[0].tableName shouldBe "orders"
        result.triggers[0].columnName shouldBe "invoice_number"
    }

    test("listPotentialSupportTriggers decodes escaped sequence literals and percent-encoded markers") {
        val sequenceName = "odd seq\\'\u03a9"
        val tableName = "orders*/archive"
        val columnName = "invoice*/number"
        val sequenceLiteral = SqlIdentifiers.quoteStringLiteral(sequenceName.replace("\\", "\\\\"))

        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns listOf(
            mapOf(
                "trigger_name" to MysqlSequenceNaming.triggerName(tableName, columnName),
                "action_timing" to "BEFORE",
                "event_manipulation" to "INSERT",
                "event_object_table" to tableName,
                "action_statement" to (
                    "/* d-migrate:mysql-sequence-v1 object=sequence-trigger " +
                        "sequence=odd%20seq%5C%27%CE%A9 table=orders%2A%2Farchive " +
                        "column=invoice%2A%2Fnumber */ " +
                        "IF NEW.`invoice*/number` IS NULL THEN " +
                        "SET NEW.`invoice*/number` = `dmg_nextval`($sequenceLiteral); END IF;"
                    ),
            ),
        )

        val result = MysqlMetadataQueries.listPotentialSupportTriggers(jdbc, "mydb")

        result.triggers shouldHaveSize 1
        result.triggers[0].state shouldBe SupportTriggerState.CONFIRMED
        result.triggers[0].tableName shouldBe tableName
        result.triggers[0].columnName shouldBe columnName
        result.triggers[0].sequenceName shouldBe sequenceName
    }

    test("listPotentialSupportTriggers falls back to SHOW CREATE TRIGGER when action_statement is degraded") {
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns listOf(
            mapOf(
                "trigger_name" to "dmg_seq_orders_invoice_number_7b0a7b2f55_bi",
                "action_timing" to "BEFORE",
                "event_manipulation" to "INSERT",
                "event_object_table" to "orders",
                "action_statement" to "SET NEW.invoice_number = dmg_nextval('invoice_seq')",
            ),
        )
        every { jdbc.querySingle(match { it.startsWith("SHOW CREATE TRIGGER") }) } returns mapOf(
            "SQL Original Statement" to (
                "BEGIN " +
                    "/* d-migrate:mysql-sequence-v1 object=sequence-trigger " +
                    "sequence=invoice_seq table=orders column=invoice_number */ " +
                    "IF NEW.`invoice_number` IS NULL THEN " +
                    "SET NEW.`invoice_number` = `dmg_nextval`('invoice_seq'); END IF; END"
                ),
        )

        val result = MysqlMetadataQueries.listPotentialSupportTriggers(jdbc, "mydb")

        result.accessible shouldBe true
        result.triggers shouldHaveSize 1
        result.triggers[0].state shouldBe SupportTriggerState.CONFIRMED
        result.triggers[0].tableName shouldBe "orders"
        result.triggers[0].columnName shouldBe "invoice_number"
        result.triggers[0].sequenceName shouldBe "invoice_seq"
    }

    test("listPotentialSupportTriggers returns MISSING_MARKER when marker absent") {
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns listOf(
            mapOf("trigger_name" to "dmg_seq_orders_col_abc1234567_bi",
                "action_timing" to "BEFORE", "event_manipulation" to "INSERT",
                "event_object_table" to "orders",
                "action_statement" to "BEGIN SET NEW.col = 1; END"),
        )
        every { jdbc.querySingle(match { it.startsWith("SHOW CREATE TRIGGER") }) } returns null
        val result = MysqlMetadataQueries.listPotentialSupportTriggers(jdbc, "mydb")
        result.triggers[0].state shouldBe SupportTriggerState.MISSING_MARKER
    }

    test("listPotentialSupportTriggers returns NON_CANONICAL for wrong timing") {
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns listOf(
            mapOf("trigger_name" to "dmg_seq_orders_col_abc1234567_bi",
                "action_timing" to "AFTER", "event_manipulation" to "INSERT",
                "event_object_table" to "orders",
                "action_statement" to "/* d-migrate:mysql-sequence-v1 */ dmg_nextval"),
        )
        every { jdbc.querySingle(match { it.startsWith("SHOW CREATE TRIGGER") }) } returns null
        val result = MysqlMetadataQueries.listPotentialSupportTriggers(jdbc, "mydb")
        result.triggers[0].state shouldBe SupportTriggerState.NON_CANONICAL
    }

    test("listPotentialSupportTriggers returns not accessible on query failure") {
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } throws
            RuntimeException("access denied")
        val result = MysqlMetadataQueries.listPotentialSupportTriggers(jdbc, "mydb")
        result.accessible shouldBe false
        result.triggers.shouldBeEmpty()
    }
})
