package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize as mapShouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

class MysqlSchemaReaderTest : FunSpec({

    // ── shared mocks ───────────────────────────────

    val conn = mockk<Connection>(relaxUnitFun = true)
    val pool = mockk<ConnectionPool> {
        every { borrow() } returns conn
    }
    val jdbc = mockk<JdbcOperations>()
    val reader = MysqlSchemaReader(jdbcFactory = { jdbc })

    // Mock currentDatabase(conn) — uses conn.catalog
    every { conn.catalog } returns "mydb"

    // Mock lowerCaseTableNames(conn) — raw JDBC
    val stmt = mockk<Statement>(relaxUnitFun = true)
    val rs = mockk<ResultSet>(relaxUnitFun = true)
    every { conn.createStatement() } returns stmt
    every { stmt.executeQuery("SELECT @@lower_case_table_names") } returns rs
    every { rs.next() } returns true
    every { rs.getInt(1) } returns 0

    // ── helper: set up default empty responses ─────

    fun stubEmptyDefaults() {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.views") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("VIEW_ROUTINE_USAGE") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns emptyList()
        // Support-table existence check → not found by default (uses querySingle)
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns null
    }

    fun stubTableQueries() {
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("referential_constraints") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("CHECK") }, any(), any()) } returns emptyList()
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns null
    }

    // ── tests ──────────────────────────────────────

    test("read returns schema with single table") {
        stubEmptyDefaults()
        stubTableQueries()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
            mapOf("column_name" to "name", "data_type" to "varchar", "column_type" to "varchar(255)",
                "is_nullable" to "YES", "column_default" to null, "ordinal_position" to 2,
                "extra" to "", "character_maximum_length" to 255,
                "numeric_precision" to null, "numeric_scale" to null),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns
            mapOf("engine" to "InnoDB")

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        result.schema.name shouldContain "mysql"
        result.schema.tables.mapShouldHaveSize(1)
        result.schema.tables shouldContainKey "users"

        val table = result.schema.tables["users"]!!
        table.primaryKey shouldBe listOf("id")
        table.columns.mapShouldHaveSize(2)
        table.metadata.shouldNotBeNull()
        table.metadata!!.engine shouldBe "InnoDB"

        val idCol = table.columns["id"]!!
        idCol.required shouldBe false // PK
        idCol.default shouldBe null // auto_increment → no default
    }

    test("read with empty database returns empty collections") {
        stubEmptyDefaults()

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        result.schema.tables.size shouldBe 0
        result.notes.shouldBeEmpty()
    }

    test("read includes views when enabled") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.views") }, any()) } returns listOf(
            mapOf("table_name" to "active_users", "view_definition" to "SELECT * FROM users WHERE active = 1"),
        )

        val result = reader.read(pool, SchemaReadOptions(includeFunctions = false,
            includeProcedures = false, includeTriggers = false))

        result.schema.views.mapShouldHaveSize(1)
        result.schema.views["active_users"]!!.sourceDialect shouldBe "mysql"
    }

    test("read includes functions with parameters") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf("routine_name" to "add_nums", "routine_type" to "FUNCTION",
                "data_type" to "int", "dtd_identifier" to "int",
                "routine_definition" to "RETURN a + b;", "is_deterministic" to "YES",
                "routine_body" to "SQL"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any(), any()) } returns listOf(
            mapOf("parameter_name" to "a", "data_type" to "int", "dtd_identifier" to "int",
                "parameter_mode" to "IN", "ordinal_position" to 1),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeProcedures = false, includeTriggers = false))

        result.schema.functions.mapShouldHaveSize(1)
        val func = result.schema.functions.values.first()
        func.parameters shouldHaveSize 1
        func.deterministic shouldBe true
        func.sourceDialect shouldBe "mysql"
    }

    test("read includes procedures") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns listOf(
            mapOf("routine_name" to "do_work", "routine_type" to "PROCEDURE",
                "routine_definition" to "BEGIN END;", "routine_body" to "SQL"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any(), any()) } returns emptyList()

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeFunctions = false, includeTriggers = false))

        result.schema.procedures.mapShouldHaveSize(1)
        result.schema.procedures.values.first().sourceDialect shouldBe "mysql"
    }

    test("read includes triggers") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns listOf(
            mapOf("trigger_name" to "trg_audit", "event_object_table" to "users",
                "action_timing" to "BEFORE", "event_manipulation" to "UPDATE",
                "action_orientation" to "ROW",
                "action_statement" to "SET NEW.updated_at = NOW()"),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeFunctions = false, includeProcedures = false))

        result.schema.triggers.mapShouldHaveSize(1)
        val trigger = result.schema.triggers.values.first()
        trigger.table shouldBe "users"
        trigger.event shouldBe TriggerEvent.UPDATE
        trigger.timing shouldBe TriggerTiming.BEFORE
        trigger.sourceDialect shouldBe "mysql"
    }

    test("read table with bigint auto_increment produces mapping note") {
        stubEmptyDefaults()
        stubTableQueries()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "big_table", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "bigint", "column_type" to "bigint(20)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 19, "numeric_scale" to 0),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns
            mapOf("engine" to "InnoDB")

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        // bigint auto_increment produces R300 mapping note
        result.notes.any { it.code == "R300" } shouldBe true
    }

    test("read table with required column and unique index") {
        stubEmptyDefaults()
        stubTableQueries()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
            mapOf("column_name" to "email", "data_type" to "varchar", "column_type" to "varchar(255)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 2,
                "extra" to "", "character_maximum_length" to 255,
                "numeric_precision" to null, "numeric_scale" to null),
            mapOf("column_name" to "bio", "data_type" to "text", "column_type" to "text",
                "is_nullable" to "YES", "column_default" to "'n/a'", "ordinal_position" to 3,
                "extra" to "", "character_maximum_length" to 65535,
                "numeric_precision" to null, "numeric_scale" to null),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        // Single-column unique index
        every { jdbc.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns listOf(
            mapOf("index_name" to "idx_email", "column_name" to "email",
                "non_unique" to 0, "seq_in_index" to 1, "index_type" to "BTREE"),
        )
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns
            mapOf("engine" to "InnoDB")

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val table = result.schema.tables["users"]!!
        val emailCol = table.columns["email"]!!
        emailCol.required shouldBe true
        emailCol.unique shouldBe true

        val bioCol = table.columns["bio"]!!
        bioCol.required shouldBe false
    }

    test("read table with multi-column index and check constraint") {
        stubEmptyDefaults()
        stubTableQueries()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
            mapOf("column_name" to "age", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 2,
                "extra" to "", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        // Non-unique index (HASH type)
        every { jdbc.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns listOf(
            mapOf("index_name" to "idx_age", "column_name" to "age",
                "non_unique" to 1, "seq_in_index" to 1, "index_type" to "HASH"),
        )
        every { jdbc.queryList(match { it.contains("CHECK") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "chk_age", "check_clause" to "(age > 0)"),
        )
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns
            mapOf("engine" to "InnoDB")

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val table = result.schema.tables["users"]!!
        table.indices shouldHaveSize 1
        table.indices[0].type shouldBe IndexType.HASH
        table.indices[0].unique shouldBe false
        table.constraints shouldHaveSize 1
        table.constraints[0].type shouldBe ConstraintType.CHECK
    }

    test("read table with FK where index name differs from FK name adds note") {
        stubEmptyDefaults()
        stubTableQueries()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "orders", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
            mapOf("column_name" to "user_id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 2,
                "extra" to "", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        every { jdbc.queryList(match { it.contains("referential_constraints") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "fk_user", "column_name" to "user_id",
                "referenced_table_name" to "users", "referenced_column_name" to "id",
                "delete_rule" to "CASCADE", "update_rule" to "NO ACTION"),
        )
        // Index with DIFFERENT name but same columns as FK
        every { jdbc.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns listOf(
            mapOf("index_name" to "idx_user_id", "column_name" to "user_id",
                "non_unique" to 1, "seq_in_index" to 1, "index_type" to "BTREE"),
        )
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns null

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        // Index kept (name differs from FK)
        val table = result.schema.tables["orders"]!!
        table.metadata shouldBe null // no engine
        // Should have R330 note about FK-overlapping index
        result.notes.any { it.code == "R330" } shouldBe true
    }

    test("read table with multi-column FK and multi-column unique index") {
        stubEmptyDefaults()
        stubTableQueries()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "order_items", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "order_id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
            mapOf("column_name" to "product_id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 2,
                "extra" to "", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
            mapOf("column_name" to "qty", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to "1", "ordinal_position" to 3,
                "extra" to "", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "order_id"),
            mapOf("column_name" to "product_id"),
        )
        // Multi-column FK
        every { jdbc.queryList(match { it.contains("referential_constraints") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "fk_order", "column_name" to "order_id",
                "referenced_table_name" to "orders", "referenced_column_name" to "id",
                "delete_rule" to "CASCADE", "update_rule" to "CASCADE"),
            mapOf("constraint_name" to "fk_order", "column_name" to "product_id",
                "referenced_table_name" to "orders", "referenced_column_name" to "product_id",
                "delete_rule" to "CASCADE", "update_rule" to "CASCADE"),
        )
        // Multi-column unique index
        every { jdbc.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns listOf(
            mapOf("index_name" to "uq_order_product", "column_name" to "order_id",
                "non_unique" to 0, "seq_in_index" to 1, "index_type" to "BTREE"),
            mapOf("index_name" to "uq_order_product", "column_name" to "product_id",
                "non_unique" to 0, "seq_in_index" to 2, "index_type" to "BTREE"),
        )
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns
            mapOf("engine" to "InnoDB")

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val table = result.schema.tables["order_items"]!!
        // Multi-column FK → constraint (not column-level reference)
        table.constraints.any { it.type == ConstraintType.FOREIGN_KEY } shouldBe true
        // Multi-column unique → constraint
        table.constraints.any { it.type == ConstraintType.UNIQUE } shouldBe true
        // PK columns should not have required=true
        table.columns["order_id"]!!.required shouldBe false
        table.columns["product_id"]!!.required shouldBe false
        table.columns["qty"]!!.required shouldBe true
    }

    test("read function with INOUT parameter") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf("routine_name" to "transform", "routine_type" to "FUNCTION",
                "data_type" to "varchar", "dtd_identifier" to "varchar(255)",
                "routine_definition" to "BEGIN END;", "is_deterministic" to "NO",
                "routine_body" to "SQL"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any(), any()) } returns listOf(
            mapOf("parameter_name" to "val", "data_type" to "varchar", "dtd_identifier" to "varchar(255)",
                "parameter_mode" to "INOUT", "ordinal_position" to 1),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeProcedures = false, includeTriggers = false))

        val func = result.schema.functions.values.first()
        func.parameters[0].direction shouldBe ParameterDirection.INOUT
        func.deterministic shouldBe false
    }

    test("read procedure with OUT parameter") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns listOf(
            mapOf("routine_name" to "get_count", "routine_type" to "PROCEDURE",
                "routine_definition" to "BEGIN END;", "routine_body" to "SQL"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any(), any()) } returns listOf(
            mapOf("parameter_name" to "cnt", "data_type" to "int", "dtd_identifier" to "int",
                "parameter_mode" to "OUT", "ordinal_position" to 1),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeFunctions = false, includeTriggers = false))

        val proc = result.schema.procedures.values.first()
        proc.parameters[0].direction shouldBe ParameterDirection.OUT
    }

    test("read trigger with STATEMENT orientation") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns listOf(
            mapOf("trigger_name" to "trg_cleanup", "event_object_table" to "logs",
                "action_timing" to "AFTER", "event_manipulation" to "DELETE",
                "action_orientation" to "STATEMENT",
                "action_statement" to "CALL cleanup()"),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeFunctions = false, includeProcedures = false))

        val trigger = result.schema.triggers.values.first()
        trigger.event shouldBe TriggerEvent.DELETE
        trigger.forEach shouldBe TriggerForEach.STATEMENT
    }

    test("read function with null parameter_name uses fallback") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf("routine_name" to "anon_fn", "routine_type" to "FUNCTION",
                "data_type" to "void", "dtd_identifier" to null,
                "routine_definition" to null, "is_deterministic" to "NO",
                "routine_body" to "SQL"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any(), any()) } returns listOf(
            mapOf("parameter_name" to null, "data_type" to "int", "dtd_identifier" to "int",
                "parameter_mode" to "IN", "ordinal_position" to 1),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeProcedures = false, includeTriggers = false))

        val func = result.schema.functions.values.first()
        func.parameters[0].name shouldBe "p1"
        func.returns shouldBe null // null dtd_identifier
        func.body shouldBe null
    }

    test("read with lowerCaseTableNames=1 normalizes identifiers") {
        val conn2 = mockk<Connection>(relaxUnitFun = true)
        val pool2 = mockk<ConnectionPool> { every { borrow() } returns conn2 }
        val jdbc2 = mockk<JdbcOperations>()
        val reader2 = MysqlSchemaReader(jdbcFactory = { jdbc2 })

        every { conn2.catalog } returns "MyDB"
        val stmt2 = mockk<Statement>(relaxUnitFun = true)
        val rs2 = mockk<ResultSet>(relaxUnitFun = true)
        every { conn2.createStatement() } returns stmt2
        every { stmt2.executeQuery("SELECT @@lower_case_table_names") } returns rs2
        every { rs2.next() } returns true
        every { rs2.getInt(1) } returns 1

        // With lctn=1, identifiers are lowercased
        every { jdbc2.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "Users", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        // Table queries for lowercased name
        every { jdbc2.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
        )
        every { jdbc2.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        every { jdbc2.queryList(match { it.contains("referential_constraints") }, any(), any()) } returns emptyList()
        every { jdbc2.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns emptyList()
        every { jdbc2.queryList(match { it.contains("CHECK") }, any(), any()) } returns emptyList()
        every { jdbc2.querySingle(match { it.contains("engine") }, any(), any()) } returns null
        every { jdbc2.queryList(match { it.contains("information_schema.views") }, any()) } returns emptyList()
        every { jdbc2.queryList(match { it.contains("VIEW_ROUTINE_USAGE") }, any()) } returns emptyList()
        every { jdbc2.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns emptyList()
        every { jdbc2.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns emptyList()
        every { jdbc2.queryList(match { it.contains("information_schema.triggers") }, any()) } returns emptyList()

        val result = reader2.read(pool2, SchemaReadOptions())

        // Table name preserved from original, not normalized
        result.schema.tables.keys shouldBe setOf("Users")
    }

    // ── Sequence support scan tests (0.9.4 AP 6.1) ──────────

    test("schema without dmg_sequences is compatible non-sequence case") {
        stubEmptyDefaults()
        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)
        result.notes.none { it.code == "W116" } shouldBe true
    }

    test("BASELINE: non-sequence single-table schema produces identical result with support scan") {
        // This test is the dedicated regression baseline per §7.14:
        // an established non-sequence fixture must produce exactly the same
        // schema objects, notes, and error behavior as before the support scan.
        stubEmptyDefaults()
        stubTableQueries()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
            mapOf("column_name" to "name", "data_type" to "varchar", "column_type" to "varchar(255)",
                "is_nullable" to "YES", "column_default" to null, "ordinal_position" to 2,
                "extra" to "", "character_maximum_length" to 255,
                "numeric_precision" to null, "numeric_scale" to null),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns
            mapOf("engine" to "InnoDB")

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        // Schema objects: exactly one table, no sequences, no extra objects
        result.schema.tables.keys shouldBe setOf("users")
        result.schema.tables["users"]!!.columns.keys shouldBe setOf("id", "name")
        result.schema.tables["users"]!!.primaryKey shouldBe listOf("id")
        result.schema.views.size shouldBe 0
        result.schema.functions.size shouldBe 0
        result.schema.procedures.size shouldBe 0
        result.schema.triggers.size shouldBe 0
        result.schema.sequences.size shouldBe 0

        // Notes: no W116, no support-scan artifacts
        result.notes.none { it.code == "W116" } shouldBe true
        result.notes.none { it.code.startsWith("W1") && it.code != "W100" && it.code != "W102" && it.code != "W103" } shouldBe true

        // Skipped: empty
        result.skippedObjects.shouldBeEmpty()
    }

    test("scanSequenceSupport returns MISSING when dmg_sequences does not exist") {
        stubEmptyDefaults()
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns null
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)
        snapshot.supportTableState shouldBe SupportTableState.MISSING
        snapshot.sequenceRows.shouldBeEmpty()
        snapshot.diagnostics.shouldBeEmpty()
    }

    test("scanSequenceSupport returns NOT_ACCESSIBLE when existence check fails") {
        stubEmptyDefaults()
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } throws RuntimeException("access denied")
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)
        snapshot.supportTableState shouldBe SupportTableState.NOT_ACCESSIBLE
    }

    test("scanSequenceSupport returns INVALID_SHAPE when columns have wrong types") {
        stubEmptyDefaults()
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns
            mapOf("table_name" to "dmg_sequences")
        every { jdbc.queryList(match { "information_schema.columns" in it && "table_name = ?" in it && "ordinal_position" !in it }, any(), any()) } returns listOf(
            mapOf("column_name" to "managed_by", "data_type" to "varchar"),
            mapOf("column_name" to "format_version", "data_type" to "varchar"),
            mapOf("column_name" to "name", "data_type" to "varchar"),
            mapOf("column_name" to "next_value", "data_type" to "varchar"),  // wrong!
            mapOf("column_name" to "increment_by", "data_type" to "bigint"),
            mapOf("column_name" to "min_value", "data_type" to "bigint"),
            mapOf("column_name" to "max_value", "data_type" to "bigint"),
            mapOf("column_name" to "cycle_enabled", "data_type" to "tinyint"),
            mapOf("column_name" to "cache_size", "data_type" to "int"),
        )
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)
        snapshot.supportTableState shouldBe SupportTableState.INVALID_SHAPE
    }

    // ── D2 helper: stub canonical dmg_sequences shape ─────
    fun stubCanonicalShape() {
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns
            mapOf("table_name" to "dmg_sequences")
        // Shape check query has "table_name = ?" but no "ordinal_position"
        // (listColumns has ordinal_position). Match on absence of ordinal_position.
        every { jdbc.queryList(match { "information_schema.columns" in it && "table_name = ?" in it && "ordinal_position" !in it }, any(), any()) } returns listOf(
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
    }
    fun stubNoRoutinesOrTriggers() {
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns null
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns emptyList()
    }

    test("D2: valid row materializes to SequenceDefinition") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "invoice_seq", "next_value" to 1000L, "increment_by" to 1L,
                "min_value" to 1000L, "max_value" to 99999999L, "cycle_enabled" to 0,
                "cache_size" to 20),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)
        val d2 = reader.materializeSupportSequences(snapshot)
        d2.sequences.size shouldBe 1
        val seq = d2.sequences["invoice_seq"]!!
        seq.start shouldBe 1000L
        seq.increment shouldBe 1L
        seq.minValue shouldBe 1000L
        seq.maxValue shouldBe 99999999L
        seq.cycle shouldBe false
        seq.cache shouldBe 20
        seq.description shouldBe null
    }

    test("D2: foreign marker rows produce no sequences and no W116") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "other-tool", "format_version" to "v2",
                "name" to "foreign_seq", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.size shouldBe 0
        d2.notes.none { it.code == "W116" } shouldBe true
    }

    test("D2: ambiguous keys produce W116 but no SequenceDefinition") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "dup", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "dup", "next_value" to 100L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.size shouldBe 0
        d2.notes.any { it.code == "W116" && "dup" in it.objectName } shouldBe true
    }

    test("D2: invalid row does not block other valid sequences") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "good_seq", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "bad_seq", "next_value" to null, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.keys shouldBe setOf("good_seq")
        d2.notes.any { it.code == "W116" && "bad_seq" in it.objectName } shouldBe true
    }

    test("D2: increment_by = 0 is invalid") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "zero_inc", "next_value" to 1L, "increment_by" to 0L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.size shouldBe 0
        d2.notes.any { it.code == "W116" } shouldBe true
    }

    test("D2: cycle_enabled only accepts 0 and 1") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "bad_cycle", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 2, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.size shouldBe 0
    }

    test("D2: cache_size NULL is valid, negative is invalid") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "null_cache", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "neg_cache", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to -1),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.keys shouldBe setOf("null_cache")
        d2.sequences["null_cache"]!!.cache shouldBe null
    }

    test("D2: cache_size > Int.MAX_VALUE invalidates row") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "big_cache", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0,
                "cache_size" to (Int.MAX_VALUE.toLong() + 1L)),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.size shouldBe 0
        d2.notes.any { it.code == "W116" && "big_cache" in it.objectName } shouldBe true
    }

    test("D2: min_value overflow invalidates row") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "overflow_min", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to java.math.BigDecimal("99999999999999999999"), "max_value" to null,
                "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.size shouldBe 0
        d2.notes.any { it.code == "W116" && "overflow_min" in it.objectName } shouldBe true
    }

    test("D2: min_value SQL NULL is valid") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "null_min", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.keys shouldBe setOf("null_min")
        d2.sequences["null_min"]!!.minValue shouldBe null
    }

    test("D2: trim collision — 'seq_a' and 'seq_a ' collide on same key") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "seq_a", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "seq_a ", "next_value" to 2L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        // Both rows collide after trim() → ambiguous key → no SequenceDefinition
        d2.sequences.size shouldBe 0
        d2.notes.any { it.code == "W116" && "seq_a" in it.objectName } shouldBe true
    }

    test("D2: cycle_enabled as Boolean (bit(1) JDBC return) is valid") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "bool_cycle", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to true, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.keys shouldBe setOf("bool_cycle")
        d2.sequences["bool_cycle"]!!.cycle shouldBe true
    }

    test("D2: cycle_enabled as Boolean false is valid") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "bool_no_cycle", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to false, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.keys shouldBe setOf("bool_no_cycle")
        d2.sequences["bool_no_cycle"]!!.cycle shouldBe false
    }

    test("D2: Double value for next_value is rejected (no lossy fallback)") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "float_seq", "next_value" to 1.5, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.size shouldBe 0
        d2.notes.any { it.code == "W116" } shouldBe true
    }

    test("D2: empty dmg_sequences produces 0 sequences and no W116") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns emptyList()
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.size shouldBe 0
        d2.notes.none { it.code == "W116" } shouldBe true
    }

    test("D2: deterministic ordering for 3+ sequences") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "c_seq", "next_value" to 3L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "a_seq", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "b_seq", "next_value" to 2L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.keys.toList() shouldBe listOf("a_seq", "b_seq", "c_seq")
    }

    test("D2: CHAR-padded markers are trimmed correctly") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate     ", "format_version" to "mysql-sequence-v1  ",
                "name" to "padded_seq  ", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.keys shouldBe setOf("padded_seq")
    }

    test("D2: missing routines produce W116 per materialized sequence") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "seq1", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        // Routines missing
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns null
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns emptyList()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.sequences.keys shouldBe setOf("seq1")
        d2.notes.any { it.code == "W116" && it.objectName == "seq1" } shouldBe true
    }

    test("D2: missing routines produce no W116 when no sequences materialized") {
        stubEmptyDefaults()
        stubCanonicalShape()
        // Only foreign rows → no sequences materialized
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "other", "format_version" to "v2",
                "name" to "foreign", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns null
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns emptyList()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val d2 = reader.materializeSupportSequences(reader.scanSequenceSupport(jdbc, "mydb", scope))
        d2.notes.none { it.code == "W116" } shouldBe true
    }

    test("D2: dmg_sequences suppressed only for AVAILABLE, not INVALID_SHAPE") {
        stubEmptyDefaults()
        stubTableQueries()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "dmg_sequences", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        // dmg_sequences exists but INVALID_SHAPE (wrong column types)
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns
            mapOf("table_name" to "dmg_sequences")
        every { jdbc.queryList(match { "information_schema.columns" in it && "table_name = ?" in it && "ordinal_position" !in it }, any(), any()) } returns listOf(
            mapOf("column_name" to "name", "data_type" to "varchar"),
            mapOf("column_name" to "value", "data_type" to "text"),
        )
        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)
        // INVALID_SHAPE → table stays as user object
        result.schema.tables shouldContainKey "dmg_sequences"
    }

    test("support objects are filtered from user schema when AVAILABLE") {
        stubEmptyDefaults()
        stubTableQueries()
        // Tables include dmg_sequences as a regular table
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
            mapOf("table_name" to "dmg_sequences", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        // dmg_sequences exists and is readable
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns
            mapOf("table_name" to "dmg_sequences")
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns emptyList()
        stubNoRoutinesOrTriggers()

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = true,
            includeProcedures = true, includeTriggers = true)
        val result = reader.read(pool, opts)

        // dmg_sequences should be filtered out from user tables (AVAILABLE)
        result.schema.tables shouldContainKey "users"
        result.schema.tables.keys shouldBe setOf("users")
    }

    test("disabled include flags produce no support objects and no extra notes") {
        stubEmptyDefaults()
        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)
        result.notes.none { it.code == "W116" } shouldBe true
        result.schema.functions.size shouldBe 0
        result.schema.triggers.size shouldBe 0
    }

    test("scanSequenceSupport scope binds queries to target database only") {
        stubEmptyDefaults()
        // dmg_sequences exists in target schema "mydb"
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns
            mapOf("table_name" to "dmg_sequences")
        every { jdbc.queryList(match { "information_schema.columns" in it && "data_type" !in it }, any(), any()) } returns
            listOf("managed_by", "format_version", "name", "next_value", "increment_by",
                "min_value", "max_value", "cycle_enabled", "cache_size").map { mapOf("column_name" to it) }
        // Row query uses schema-qualified FROM `mydb`.`dmg_sequences`
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "seq1", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns null
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns emptyList()

        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)

        // Snapshot is scoped to "mydb"
        snapshot.scope.schemaName shouldBe "mydb"
        snapshot.supportTableState shouldBe SupportTableState.AVAILABLE
        snapshot.sequenceRows shouldHaveSize 1

        // A different scope should not see these rows
        val otherScope = ReverseScope(catalogName = "other_db", schemaName = "other_db")
        // Reset stubs: other_db has no dmg_sequences
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns null
        val otherSnapshot = reader.scanSequenceSupport(jdbc, "other_db", otherScope)
        otherSnapshot.supportTableState shouldBe SupportTableState.MISSING
    }

    test("D2: NON_CANONICAL routine produces W116 per materialized sequence") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "seq1", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        // dmg_nextval has marker but wrong return type (varchar instead of bigint)
        every { jdbc.querySingle(match { "routine_name = ?" in it }, eq("mydb"), eq("dmg_nextval")) } returns
            mapOf("routine_name" to "dmg_nextval", "routine_definition" to "/* d-migrate:mysql-sequence-v1 object=nextval */",
                "data_type" to "varchar", "dtd_identifier" to "varchar(255)")
        every { jdbc.querySingle(match { "routine_name = ?" in it }, eq("mydb"), eq("dmg_setval")) } returns null
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns emptyList()

        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)
        snapshot.routineStates[MysqlSequenceNaming.NEXTVAL_ROUTINE] shouldBe SupportRoutineState.NON_CANONICAL
        // D2 materializes the sequence but emits W116 due to non-canonical routine
        val d2 = reader.materializeSupportSequences(snapshot)
        d2.sequences.keys shouldBe setOf("seq1")
        d2.notes.any { it.code == "W116" && it.objectName == "seq1" && it.hint?.contains("not canonical") == true } shouldBe true
    }

    // ── D3: Sequence defaults from triggers (0.9.4 AP 6.3) ──

    fun makeSnapshot(
        sequences: Map<String, SequenceDefinition> = mapOf("invoice_seq" to SequenceDefinition(start = 1000)),
        triggerAssessments: List<MysqlMetadataQueries.SupportTriggerAssessment> = emptyList(),
    ): MysqlSequenceSupportSnapshot {
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val triggerStates = triggerAssessments.associate { it.triggerName to it.state }
        return MysqlSequenceSupportSnapshot(
            scope = scope,
            supportTableState = SupportTableState.AVAILABLE,
            sequenceRows = emptyList(),
            ambiguousKeys = emptySet(),
            routineStates = emptyMap(),
            triggerStates = triggerStates,
            triggerAssessments = triggerAssessments,
            diagnostics = emptyList(),
        )
    }

    fun makeTable(vararg cols: Pair<String, ColumnDefinition>): Map<String, TableDefinition> = mapOf(
        "orders" to TableDefinition(columns = linkedMapOf(*cols), primaryKey = listOf("id")),
    )

    val confirmedTriggerName = MysqlSequenceNaming.triggerName("orders", "invoice_number")

    test("D3: confirmed trigger materializes SequenceNextVal on column") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger),
        )
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                confirmedTriggerName, SupportTriggerState.CONFIRMED, "orders", "invoice_number", "invoice_seq",
            ),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, snapshot.let {
            mapOf("invoice_seq" to SequenceDefinition(start = 1000))
        }, tables)
        val col = d3.enrichedTables["orders"]!!.columns["invoice_number"]!!
        col.default shouldBe DefaultValue.SequenceNextVal("invoice_seq")
        d3.confirmedTriggerNames shouldBe setOf(confirmedTriggerName)
    }

    test("D3: includeTriggers=false does not prevent D3 recognition") {
        // D3 works through the snapshot, not through user-visible triggers.
        // This test verifies that trigger assessments are processed regardless
        // of the includeTriggers flag by testing materializeSequenceDefaults directly.
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger),
        )
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                confirmedTriggerName, SupportTriggerState.CONFIRMED, "orders", "invoice_number", "invoice_seq",
            ),
        ))
        // D3 works even though no user triggers were read (includeTriggers=false scenario)
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        d3.enrichedTables["orders"]!!.columns["invoice_number"]!!.default shouldBe DefaultValue.SequenceNextVal("invoice_seq")
    }

    test("D3: MISSING_MARKER trigger produces no SequenceNextVal") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger),
        )
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                confirmedTriggerName, SupportTriggerState.MISSING_MARKER, "orders", "invoice_number", "invoice_seq",
            ),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        val col = d3.enrichedTables["orders"]!!.columns["invoice_number"]!!
        col.default shouldBe null  // not set
        d3.confirmedTriggerNames.shouldBeEmpty()
        d3.notes.any { it.code == "W116" && "orders.invoice_number" in it.objectName } shouldBe true
    }

    test("D3: trigger referencing non-materialized sequence produces no SequenceNextVal and W116") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger),
        )
        val snapshot = makeSnapshot(
            sequences = emptyMap(),  // no sequences materialized
            triggerAssessments = listOf(
                MysqlMetadataQueries.SupportTriggerAssessment(
                    confirmedTriggerName, SupportTriggerState.CONFIRMED, "orders", "invoice_number", "ghost_seq",
                ),
            ),
        )
        val d3 = reader.materializeSequenceDefaults(snapshot, emptyMap(), tables)
        val col = d3.enrichedTables["orders"]!!.columns["invoice_number"]!!
        col.default shouldBe null
        // W116 for non-materialized sequence reference
        d3.notes.any { it.code == "W116" && "orders.invoice_number" in it.objectName } shouldBe true
    }

    test("D3: conflicting existing default produces W116, no overwrite") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger, default = DefaultValue.NumberLiteral(0)),
        )
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                confirmedTriggerName, SupportTriggerState.CONFIRMED, "orders", "invoice_number", "invoice_seq",
            ),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        // Default NOT overwritten
        val col = d3.enrichedTables["orders"]!!.columns["invoice_number"]!!
        col.default shouldBe DefaultValue.NumberLiteral(0)
        // W116 emitted for the conflict
        d3.notes.any { it.code == "W116" && "conflicting" in (it.hint ?: "") } shouldBe true
        // Trigger still confirmed (suppressed despite default conflict)
        d3.confirmedTriggerNames shouldBe setOf(confirmedTriggerName)
    }

    test("D3: USER_OBJECT trigger is not suppressed and no W116") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
        )
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                "dmg_seq_orders_col_abc1234567_bi", SupportTriggerState.USER_OBJECT, "orders", null, null,
            ),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        d3.confirmedTriggerNames.shouldBeEmpty()
        d3.notes.shouldBeEmpty()
    }

    test("D3: multiple columns on same sequence") {
        val tables = mapOf("orders" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "col_a" to ColumnDefinition(type = NeutralType.BigInteger),
                "col_b" to ColumnDefinition(type = NeutralType.BigInteger),
            ),
            primaryKey = listOf("id"),
        ))
        val trigA = MysqlSequenceNaming.triggerName("orders", "col_a")
        val trigB = MysqlSequenceNaming.triggerName("orders", "col_b")
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(trigA, SupportTriggerState.CONFIRMED, "orders", "col_a", "invoice_seq"),
            MysqlMetadataQueries.SupportTriggerAssessment(trigB, SupportTriggerState.CONFIRMED, "orders", "col_b", "invoice_seq"),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        d3.enrichedTables["orders"]!!.columns["col_a"]!!.default shouldBe DefaultValue.SequenceNextVal("invoice_seq")
        d3.enrichedTables["orders"]!!.columns["col_b"]!!.default shouldBe DefaultValue.SequenceNextVal("invoice_seq")
        d3.confirmedTriggerNames shouldBe setOf(trigA, trigB)
    }

    test("D3: degraded trigger does not block other confirmed triggers") {
        val tables = mapOf("orders" to TableDefinition(
            columns = linkedMapOf(
                "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
                "good_col" to ColumnDefinition(type = NeutralType.BigInteger),
                "bad_col" to ColumnDefinition(type = NeutralType.BigInteger),
            ),
            primaryKey = listOf("id"),
        ))
        val goodTrig = MysqlSequenceNaming.triggerName("orders", "good_col")
        val badTrig = MysqlSequenceNaming.triggerName("orders", "bad_col")
        val snapshot = makeSnapshot(
            sequences = mapOf(
                "good_seq" to SequenceDefinition(start = 1),
                "bad_seq" to SequenceDefinition(start = 1),
            ),
            triggerAssessments = listOf(
                MysqlMetadataQueries.SupportTriggerAssessment(goodTrig, SupportTriggerState.CONFIRMED, "orders", "good_col", "good_seq"),
                MysqlMetadataQueries.SupportTriggerAssessment(badTrig, SupportTriggerState.NON_CANONICAL, "orders", "bad_col", "bad_seq"),
            ),
        )
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf(
            "good_seq" to SequenceDefinition(start = 1),
            "bad_seq" to SequenceDefinition(start = 1),
        ), tables)
        d3.enrichedTables["orders"]!!.columns["good_col"]!!.default shouldBe DefaultValue.SequenceNextVal("good_seq")
        d3.enrichedTables["orders"]!!.columns["bad_col"]!!.default shouldBe null
        d3.confirmedTriggerNames shouldBe setOf(goodTrig)
    }

    test("D3: MISSING_MARKER without verankerbare Spalte produces no W116") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
        )
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            // columnName = null → not verankerbar
            MysqlMetadataQueries.SupportTriggerAssessment(
                "dmg_seq_orders_unknown_abc1234567_bi", SupportTriggerState.MISSING_MARKER, "orders", null, null,
            ),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        d3.notes.shouldBeEmpty()
    }

    test("D3: NOT_ACCESSIBLE trigger scan preserves D2 sequences with W116") {
        // When trigger scan is not accessible, D2 sequences survive
        // but no column defaults are set and W116 is emitted per sequence
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = MysqlSequenceSupportSnapshot(
            scope = scope,
            supportTableState = SupportTableState.AVAILABLE,
            sequenceRows = emptyList(),
            ambiguousKeys = emptySet(),
            routineStates = emptyMap(),
            triggerStates = emptyMap(),
            triggerAssessments = emptyList(), // scan inaccessible → no assessments
            diagnostics = listOf(
                SupportDiagnostic(
                    key = SequenceDiagnosticKey(scope, "invoice_seq"),
                    causes = listOf("support trigger metadata is not accessible"),
                    emitsW116 = true,
                ),
            ),
        )
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger),
        )
        val d3 = reader.materializeSequenceDefaults(
            snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables,
        )
        // Sequences survived, but no column defaults
        d3.enrichedTables["orders"]!!.columns["invoice_number"]!!.default shouldBe null
        d3.confirmedTriggerNames.shouldBeEmpty()
        // W116 emitted for trigger inaccessibility
        d3.notes.any { it.code == "W116" && "invoice_seq" in it.objectName } shouldBe true
    }

    test("D3: confirmed trigger with wrong forward-verified name is not confirmed") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger),
        )
        // Trigger name doesn't match MysqlSequenceNaming.triggerName("orders", "invoice_number")
        val wrongName = "dmg_seq_orders_invoicenumber_0000000000_bi"
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                wrongName, SupportTriggerState.CONFIRMED, "orders", "invoice_number", "invoice_seq",
            ),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        // Forward verification failed → no SequenceNextVal, trigger NOT confirmed
        d3.enrichedTables["orders"]!!.columns["invoice_number"]!!.default shouldBe null
        d3.confirmedTriggerNames.shouldBeEmpty()
        d3.notes.any { it.code == "W116" } shouldBe true
    }

    // ── existing tests (unchanged baseline) ───────

    test("read with FK backing index suppression") {
        stubEmptyDefaults()
        stubTableQueries()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "orders", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
            mapOf("column_name" to "user_id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 2,
                "extra" to "", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns listOf(
            mapOf("column_name" to "id"),
        )
        every { jdbc.queryList(match { it.contains("referential_constraints") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "fk_user", "column_name" to "user_id",
                "referenced_table_name" to "users", "referenced_column_name" to "id",
                "delete_rule" to "CASCADE", "update_rule" to "NO ACTION"),
        )
        // Index with same name as FK → should be suppressed
        every { jdbc.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns listOf(
            mapOf("index_name" to "fk_user", "column_name" to "user_id",
                "non_unique" to 1, "seq_in_index" to 1, "index_type" to "BTREE"),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val table = result.schema.tables["orders"]!!
        // FK backing index should be suppressed
        table.indices.shouldBeEmpty()
        // FK should be lifted to column-level reference
        val userIdCol = table.columns["user_id"]!!
        userIdCol.references.shouldNotBeNull()
        userIdCol.references!!.table shouldBe "users"
    }
})
