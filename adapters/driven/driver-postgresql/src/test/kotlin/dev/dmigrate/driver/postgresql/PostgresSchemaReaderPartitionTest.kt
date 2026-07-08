package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

/**
 * Partitions-spezifische Reverse-Reader-Tests (ADR 0019, AP1/AP2a) — eigene Spec,
 * damit PostgresSchemaReaderTest unter der LargeClass-Schwelle bleibt (echte
 * Aufteilung, kein `@Suppress`).
 */
class PostgresSchemaReaderPartitionTest : FunSpec({

    // ── shared mocks ───────────────────────────────

    val conn = mockk<Connection>(relaxUnitFun = true)
    val pool = mockk<ConnectionPool> {
        every { borrow() } returns JdbcDatabaseConnection(conn)
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
        every { jdbc.queryList(match { it.contains("pg_get_viewdef") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("refobjsubid") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_depend") && it.contains("pg_proc") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("view_name") && it.contains("format_type") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns emptyList()
        // N7: user-defined aggregates from pg_aggregate.
        every { jdbc.queryList(match { it.contains("pg_aggregate") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns emptyList()
        // E.1 Slice D.2: trigger ↔ function edges via pg_trigger.tgfoid → pg_proc.oid.
        every { jdbc.queryList(match { it.contains("pg_trigger") && it.contains("tgfoid") }, any()) } returns emptyList()
        // E.1 Slice E: routine identity attributes from pg_proc.
        every {
            jdbc.queryList(match { it.contains("prosecdef") && it.contains("proconfig") }, any())
        } returns emptyList()
    }

    fun stubTableQueries(columns: List<Map<String, Any?>>, pkColumns: List<String>) {
        every { jdbc.queryList(match { it.contains("information_schema.columns") }, any(), any()) } returns columns
        // VA2: geometry_columns probe — no PostGIS view present by default.
        every { jdbc.queryList(match { it.contains("to_regclass('geometry_columns')") }) } returns
            listOf(mapOf("r" to null))
        every { jdbc.queryList(match { it.contains("FROM geometry_columns") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("contype = 'p'") }, any(), any()) } returns
            pkColumns.map { mapOf("column_name" to it) }
        every { jdbc.queryList(match { it.contains("contype = 'f'") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("contype = 'u'") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("CHECK") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("pg_index") }, any(), any()) } returns emptyList()
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns null
        // AP1: partition children (pg_inherits + relpartbound) — none by default.
        every { jdbc.queryList(match { it.contains("relpartbound") }, any(), any()) } returns emptyList()
        // AP2a: inherited (parent-propagated) index names per partition — none by default.
        every { jdbc.queryList(match { it.contains("cix.indexrelid") }, any(), any()) } returns emptyList()
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

    test("read partitioned table captures children + parsed bounds (AP1)") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "events", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        stubTableQueries(listOf(
            mapOf("column_name" to "created_at", "data_type" to "timestamp with time zone",
                "udt_name" to "timestamptz", "is_nullable" to "NO", "column_default" to null,
                "ordinal_position" to 1, "character_maximum_length" to null,
                "numeric_precision" to null, "numeric_scale" to null,
                "is_identity" to "NO", "identity_generation" to null),
        ), listOf("created_at"))
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns
            mapOf("partstrat" to "r", "key_columns" to "{created_at}")
        every { jdbc.queryList(match { it.contains("relpartbound") }, any(), any()) } returns listOf(
            mapOf("partition_name" to "events_2022_01",
                "bound_expr" to "FOR VALUES FROM ('2022-01-01 00:00:00+00'::timestamp with time zone) " +
                    "TO ('2022-02-01 00:00:00+00'::timestamp with time zone)"),
            mapOf("partition_name" to "events_2022_02",
                "bound_expr" to "FOR VALUES FROM ('2022-02-01 00:00:00+00'::timestamp with time zone) " +
                    "TO ('2022-03-01 00:00:00+00'::timestamp with time zone)"),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val partitions = result.schema.tables["events"]!!.partitioning!!.partitions
        partitions shouldHaveSize 2
        partitions[0].name shouldBe "events_2022_01"
        partitions[0].from shouldBe listOf(PartitionBound.Value("'2022-01-01 00:00:00+00'"))
        partitions[0].to shouldBe listOf(PartitionBound.Value("'2022-02-01 00:00:00+00'"))
        partitions[1].name shouldBe "events_2022_02"
    }

    test("partition carries child-local indices, parent-propagated excluded (AP2a)") {
        stubEmptyDefaults()
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns listOf(
            mapOf("table_name" to "events", "table_schema" to "public", "table_type" to "BASE TABLE"),
        )
        stubTableQueries(listOf(
            mapOf("column_name" to "created_at", "data_type" to "timestamp with time zone",
                "udt_name" to "timestamptz", "is_nullable" to "NO", "column_default" to null,
                "ordinal_position" to 1, "character_maximum_length" to null,
                "numeric_precision" to null, "numeric_scale" to null,
                "is_identity" to "NO", "identity_generation" to null),
        ), listOf("created_at"))
        every { jdbc.querySingle(match { it.contains("pg_partitioned_table") }, any(), any()) } returns
            mapOf("partstrat" to "r", "key_columns" to "{created_at}")
        every { jdbc.queryList(match { it.contains("relpartbound") }, any(), any()) } returns listOf(
            mapOf("partition_name" to "events_2022_01",
                "bound_expr" to "FOR VALUES FROM ('2022-01-01 00:00:00+00') TO ('2022-02-01 00:00:00+00')"),
        )
        // listIndices returns a child-local index + a parent-propagated one.
        every { jdbc.queryList(match { it.contains("pg_index") }, any(), any()) } returns listOf(
            mapOf("index_name" to "idx_events_2022_01_created", "columns" to "{created_at}",
                "is_unique" to false, "index_type" to "btree"),
            mapOf("index_name" to "events_propagated_idx", "columns" to "{created_at}",
                "is_unique" to false, "index_type" to "btree"),
        )
        // inheritance query marks events_propagated_idx as parent-propagated → excluded.
        every { jdbc.queryList(match { it.contains("cix.indexrelid") }, any(), any()) } returns listOf(
            mapOf("index_name" to "events_propagated_idx"),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val partition = result.schema.tables["events"]!!.partitioning!!.partitions.single()
        partition.indices.map { it.name } shouldBe listOf("idx_events_2022_01_created")
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
})
