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
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns emptyList()
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
        every { jdbc2.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns emptyList()
        every { jdbc2.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns emptyList()
        every { jdbc2.queryList(match { it.contains("information_schema.triggers") }, any()) } returns emptyList()

        val result = reader2.read(pool2, SchemaReadOptions())

        // Table name preserved from original, not normalized
        result.schema.tables.keys shouldBe setOf("Users")
    }

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
