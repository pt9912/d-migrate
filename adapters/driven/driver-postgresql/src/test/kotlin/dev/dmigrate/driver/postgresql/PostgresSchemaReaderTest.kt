package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize as mapShouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

class PostgresSchemaReaderTest : FunSpec({

    // ── shared mocks ───────────────────────────────

    val conn = mockk<Connection>(relaxUnitFun = true)
    val pool = mockk<ConnectionPool> {
        every { borrow() } returns conn
    }
    val jdbc = mockk<JdbcOperations>()
    val reader = PostgresSchemaReader(jdbcFactory = { jdbc })

    // Mock currentSchema(conn)
    val stmt = mockk<Statement>(relaxUnitFun = true)
    val rs = mockk<ResultSet>(relaxUnitFun = true)
    every { conn.createStatement() } returns stmt
    every { stmt.executeQuery("SELECT current_schema()") } returns rs
    every { rs.next() } returns true
    every { rs.getString(1) } returns "public"
    every { conn.catalog } returns "testdb"

    // ── helper: set up default empty responses ─────

    fun stubEmptyDefaults() {
        // Tables
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns emptyList()
        // Sequences
        every { jdbc.queryList(match { it.contains("information_schema.sequences") }, any()) } returns emptyList()
        // Custom types
        every { jdbc.queryList(match { it.contains("pg_enum") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("typtype = 'd'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("typtype = 'c'") }, any()) } returns emptyList()
        // Extensions
        every { jdbc.queryList(match { it.contains("pg_extension") }) } returns emptyList()
        // Views, view→function deps, functions, procedures, triggers
        every { jdbc.queryList(match { it.contains("information_schema.views") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_depend") && it.contains("pg_proc") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns emptyList()
    }

    fun stubTableQueries(columns: List<Map<String, Any?>>, pkColumns: List<String>) {
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns columns
        every { jdbc.queryList(match { it.contains("PRIMARY KEY") }, any(), any()) } returns
            pkColumns.map { mapOf("column_name" to it) }
        every { jdbc.queryList(match { it.contains("pg_constraint") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { "UNIQUE" in it && "pg_constraint" !in it }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("CHECK") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_index") }, any(), any()) } returns emptyList()
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns null
    }

    // ── tests ──────────────────────────────────────

    test("read returns schema with single table") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "users", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        stubTableQueries(listOf(
            mapOf("column_name" to "id", "data_type" to "integer", "udt_name" to "int4",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "character_maximum_length" to null, "numeric_precision" to 32, "numeric_scale" to 0,
                "is_identity" to "NO", "identity_generation" to null),
            mapOf("column_name" to "name", "data_type" to "character varying", "udt_name" to "varchar",
                "is_nullable" to "YES", "column_default" to null, "ordinal_position" to 2,
                "character_maximum_length" to 255, "numeric_precision" to null, "numeric_scale" to null,
                "is_identity" to "NO", "identity_generation" to null),
        ), listOf("id"))

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        result.schema.name shouldContain "postgresql"
        result.schema.tables.mapShouldHaveSize(1)
        result.schema.tables shouldContainKey "users"

        val table = result.schema.tables["users"]!!
        table.primaryKey shouldBe listOf("id")
        table.columns.mapShouldHaveSize(2)

        val idCol = table.columns["id"]!!
        idCol.required shouldBe false // PK columns have required=false
        idCol.unique shouldBe false   // PK columns have unique=false

        val nameCol = table.columns["name"]!!
        nameCol.required shouldBe false // nullable
    }

    test("read includes sequences") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.sequences") }, any()) } returns listOf(
            mapOf("sequence_name" to "users_id_seq", "start_value" to "1", "increment" to "1",
                "minimum_value" to "1", "maximum_value" to "9223372036854775807",
                "cycle_option" to "NO", "cache_size" to 1L),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        result.schema.sequences.mapShouldHaveSize(1)
        val seq = result.schema.sequences["users_id_seq"]!!
        seq.start shouldBe 1L
        seq.increment shouldBe 1L
        seq.cycle shouldBe false
        seq.cache shouldBe 1
    }

    test("read includes enum custom types") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("pg_enum") }, any()) } returns listOf(
            mapOf("typname" to "status", "enumlabel" to "active"),
            mapOf("typname" to "status", "enumlabel" to "inactive"),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        result.schema.customTypes.mapShouldHaveSize(1)
        val enumType = result.schema.customTypes["status"]!!
        enumType.kind shouldBe CustomTypeKind.ENUM
        enumType.values shouldBe listOf("active", "inactive")
    }

    test("read adds extension notes") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("pg_extension") }) } returns listOf(
            mapOf("extname" to "uuid-ossp"),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        result.notes shouldHaveSize 1
        result.notes[0].code shouldBe "R400"
        result.notes[0].objectName shouldBe "uuid-ossp"
    }

    test("read includes views when enabled") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.views") }, any()) } returns listOf(
            mapOf("table_name" to "active_users", "view_definition" to "SELECT * FROM users WHERE active"),
        )

        val result = reader.read(pool, SchemaReadOptions(includeFunctions = false,
            includeProcedures = false, includeTriggers = false))

        result.schema.views.mapShouldHaveSize(1)
        result.schema.views["active_users"]!!.sourceDialect shouldBe "postgresql"
    }

    test("read includes functions with parameters") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf("routine_name" to "add_numbers", "specific_name" to "add_numbers_1",
                "routine_type" to "FUNCTION", "data_type" to "integer",
                "type_udt_name" to "int4", "external_language" to "plpgsql",
                "routine_definition" to "BEGIN RETURN a + b; END;", "is_deterministic" to "YES"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any()) } returns listOf(
            mapOf("parameter_name" to "a", "data_type" to "integer", "udt_name" to "int4",
                "parameter_mode" to "IN", "ordinal_position" to 1),
            mapOf("parameter_name" to "b", "data_type" to "integer", "udt_name" to "int4",
                "parameter_mode" to "IN", "ordinal_position" to 2),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeProcedures = false, includeTriggers = false))

        result.schema.functions.mapShouldHaveSize(1)
        val func = result.schema.functions.values.first()
        func.parameters shouldHaveSize 2
        func.parameters[0].name shouldBe "a"
        func.parameters[0].direction shouldBe ParameterDirection.IN
        func.returns.shouldNotBeNull()
        func.deterministic shouldBe true
        func.sourceDialect shouldBe "postgresql"
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
        trigger.event shouldBe TriggerEvent.INSERT
        trigger.timing shouldBe TriggerTiming.AFTER
        trigger.forEach shouldBe TriggerForEach.ROW
        trigger.sourceDialect shouldBe "postgresql"
    }

    test("read with partitioned table") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "events", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        stubTableQueries(listOf(
            mapOf("column_name" to "id", "data_type" to "integer", "udt_name" to "int4",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "character_maximum_length" to null, "numeric_precision" to 32, "numeric_scale" to 0,
                "is_identity" to "NO", "identity_generation" to null),
            mapOf("column_name" to "created_at", "data_type" to "timestamp without time zone",
                "udt_name" to "timestamp", "is_nullable" to "NO", "column_default" to null,
                "ordinal_position" to 2, "character_maximum_length" to null,
                "numeric_precision" to null, "numeric_scale" to null,
                "is_identity" to "NO", "identity_generation" to null),
        ), listOf("id"))

        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns
            mapOf("partstrat" to "r", "key_columns" to "{created_at}")

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val table = result.schema.tables["events"]!!
        table.partitioning.shouldNotBeNull()
        table.partitioning!!.type shouldBe PartitionType.RANGE
        table.partitioning!!.key shouldBe listOf("created_at")
    }

    test("read with empty schema returns empty collections") {
        stubEmptyDefaults()

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        result.schema.tables.size shouldBe 0
        result.schema.sequences.size shouldBe 0
        result.schema.customTypes.size shouldBe 0
        result.notes.shouldBeEmpty()
    }

    test("read with domain and composite custom types") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("typtype = 'd'") }, any()) } returns listOf(
            mapOf("typname" to "email", "base_type" to "varchar", "numeric_precision" to null,
                "numeric_scale" to null, "domain_default" to null, "check_clause" to null),
        )
        every { jdbc.queryList(match { it.contains("typtype = 'c'") }, any()) } returns listOf(
            mapOf("typname" to "address", "attname" to "street", "attnum" to 1, "column_type" to "text"),
            mapOf("typname" to "address", "attname" to "city", "attnum" to 2, "column_type" to "text"),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        result.schema.customTypes.mapShouldHaveSize(2)
        result.schema.customTypes["email"]!!.kind shouldBe CustomTypeKind.DOMAIN
        val composite = result.schema.customTypes["address"]!!
        composite.kind shouldBe CustomTypeKind.COMPOSITE
        composite.fields!!.mapShouldHaveSize(2)
    }

    test("read includes procedures with OUT parameters") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns listOf(
            mapOf("routine_name" to "do_work", "specific_name" to "do_work_1",
                "routine_type" to "PROCEDURE", "external_language" to "plpgsql",
                "routine_definition" to "BEGIN END;"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any()) } returns listOf(
            mapOf("parameter_name" to "result", "data_type" to "text", "udt_name" to "text",
                "parameter_mode" to "OUT", "ordinal_position" to 1),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeFunctions = false, includeTriggers = false))

        val proc = result.schema.procedures.values.first()
        proc.parameters shouldHaveSize 1
        proc.parameters[0].direction shouldBe ParameterDirection.OUT
        proc.sourceDialect shouldBe "postgresql"
    }

    test("read table with FK reference on column") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "orders", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        stubTableQueries(listOf(
            mapOf("column_name" to "id", "data_type" to "integer", "udt_name" to "int4",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "character_maximum_length" to null, "numeric_precision" to 32, "numeric_scale" to 0,
                "is_identity" to "NO", "identity_generation" to null),
            mapOf("column_name" to "user_id", "data_type" to "integer", "udt_name" to "int4",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 2,
                "character_maximum_length" to null, "numeric_precision" to 32, "numeric_scale" to 0,
                "is_identity" to "NO", "identity_generation" to null),
            mapOf("column_name" to "note", "data_type" to "character varying", "udt_name" to "varchar",
                "is_nullable" to "YES", "column_default" to "'default note'", "ordinal_position" to 3,
                "character_maximum_length" to 1000, "numeric_precision" to null, "numeric_scale" to null,
                "is_identity" to "NO", "identity_generation" to null),
        ), listOf("id"))
        // FK on user_id
        every { jdbc.queryList(match { it.contains("pg_constraint") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "fk_user", "columns" to "{user_id}",
                "referenced_table" to "users", "referenced_columns" to "{id}",
                "confdeltype" to "c", "confupdtype" to "a"),
        )
        // Unique constraint on note
        every { jdbc.queryList(match { "UNIQUE" in it && "pg_constraint" !in it }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "uq_note", "column_name" to "note"),
        )
        // Check constraint
        every { jdbc.queryList(match { it.contains("CHECK") }, any(), any()) } returns listOf(
            mapOf("constraint_name" to "chk_note", "check_clause" to "(note <> '')"),
        )
        // Index
        every { jdbc.queryList(match { it.contains("pg_index") }, any(), any()) } returns listOf(
            mapOf("index_name" to "idx_user_id", "columns" to "{user_id}",
                "is_unique" to false, "index_type" to "btree"),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val table = result.schema.tables["orders"]!!
        val userIdCol = table.columns["user_id"]!!
        userIdCol.references.shouldNotBeNull()
        userIdCol.references!!.table shouldBe "users"
        userIdCol.required shouldBe true // non-PK, NOT NULL

        val noteCol = table.columns["note"]!!
        noteCol.unique shouldBe true
        noteCol.required shouldBe false // nullable

        table.indices shouldHaveSize 1
        table.indices[0].type shouldBe IndexType.BTREE
        table.constraints.any { it.type == ConstraintType.CHECK } shouldBe true
    }

    test("read table with identity column") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "items", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        stubTableQueries(listOf(
            mapOf("column_name" to "id", "data_type" to "integer", "udt_name" to "int4",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "character_maximum_length" to null, "numeric_precision" to 32, "numeric_scale" to 0,
                "is_identity" to "YES", "identity_generation" to "ALWAYS"),
        ), listOf("id"))

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val table = result.schema.tables["items"]!!
        table.columns["id"].shouldNotBeNull()
    }

    test("readPartitioning with LIST and HASH strategies") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "logs", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        stubTableQueries(listOf(
            mapOf("column_name" to "id", "data_type" to "integer", "udt_name" to "int4",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "character_maximum_length" to null, "numeric_precision" to 32, "numeric_scale" to 0,
                "is_identity" to "NO", "identity_generation" to null),
        ), listOf("id"))
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns
            mapOf("partstrat" to "l", "key_columns" to "{region}")

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        result.schema.tables["logs"]!!.partitioning!!.type shouldBe PartitionType.LIST
    }

    test("readPartitioning with HASH strategy") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "data", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        stubTableQueries(listOf(
            mapOf("column_name" to "id", "data_type" to "integer", "udt_name" to "int4",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "character_maximum_length" to null, "numeric_precision" to 32, "numeric_scale" to 0,
                "is_identity" to "NO", "identity_generation" to null),
        ), listOf("id"))
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns
            mapOf("partstrat" to "h", "key_columns" to "{id}")

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)
        result.schema.tables["data"]!!.partitioning!!.type shouldBe PartitionType.HASH
    }

    test("readPartitioning returns null for unknown strategy") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "t", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        stubTableQueries(listOf(
            mapOf("column_name" to "id", "data_type" to "integer", "udt_name" to "int4",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "character_maximum_length" to null, "numeric_precision" to 32, "numeric_scale" to 0,
                "is_identity" to "NO", "identity_generation" to null),
        ), listOf("id"))
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns
            mapOf("partstrat" to "x", "key_columns" to "{id}")

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)
        result.schema.tables["t"]!!.partitioning.shouldBeNull()
    }

    test("readPartitioning handles java.sql.Array key_columns") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "parts", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        stubTableQueries(listOf(
            mapOf("column_name" to "id", "data_type" to "integer", "udt_name" to "int4",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "character_maximum_length" to null, "numeric_precision" to 32, "numeric_scale" to 0,
                "is_identity" to "NO", "identity_generation" to null),
        ), listOf("id"))

        val sqlArray = mockk<java.sql.Array>()
        every { sqlArray.array } returns arrayOf("region", "date")
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns
            mapOf("partstrat" to "r", "key_columns" to sqlArray)

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        result.schema.tables["parts"]!!.partitioning!!.key shouldBe listOf("region", "date")
    }

    test("read function with null parameter_name uses fallback") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf("routine_name" to "anon_fn", "specific_name" to "anon_fn_1",
                "routine_type" to "FUNCTION", "data_type" to "integer",
                "type_udt_name" to "int4", "external_language" to "sql",
                "routine_definition" to "SELECT 1;", "is_deterministic" to "YES"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any()) } returns listOf(
            mapOf("parameter_name" to null, "data_type" to "integer", "udt_name" to "int4",
                "parameter_mode" to "IN", "ordinal_position" to 1),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeProcedures = false, includeTriggers = false))

        val func = result.schema.functions.values.first()
        func.parameters[0].name shouldBe "p1"
    }

    test("read function with INOUT parameter") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns listOf(
            mapOf("routine_name" to "transform", "specific_name" to "transform_1",
                "routine_type" to "FUNCTION", "data_type" to "void",
                "type_udt_name" to "void", "external_language" to "plpgsql",
                "routine_definition" to "BEGIN END;", "is_deterministic" to "NO"),
        )
        every { jdbc.queryList(match { it.contains("information_schema.parameters") }, any(), any()) } returns listOf(
            mapOf("parameter_name" to "val", "data_type" to "text", "udt_name" to "text",
                "parameter_mode" to "INOUT", "ordinal_position" to 1),
        )

        val result = reader.read(pool, SchemaReadOptions(includeViews = false,
            includeProcedures = false, includeTriggers = false))

        val func = result.schema.functions.values.first()
        func.parameters[0].direction shouldBe ParameterDirection.INOUT
        func.returns.shouldBeNull() // void
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
        trigger.event shouldBe TriggerEvent.DELETE
        trigger.timing shouldBe TriggerTiming.INSTEAD_OF
        trigger.forEach shouldBe TriggerForEach.STATEMENT
        trigger.condition shouldBe "OLD.id > 0"
    }

    test("read with sequence having string values from information_schema") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.sequences") }, any()) } returns listOf(
            mapOf("sequence_name" to "counter_seq", "start_value" to "100", "increment" to "10",
                "minimum_value" to "1", "maximum_value" to "999",
                "cycle_option" to "YES", "cache_size" to null),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val seq = result.schema.sequences["counter_seq"]!!
        seq.start shouldBe 100L
        seq.increment shouldBe 10L
        seq.minValue shouldBe 1L
        seq.maxValue shouldBe 999L
        seq.cycle shouldBe true
        seq.cache.shouldBeNull()
    }
})
