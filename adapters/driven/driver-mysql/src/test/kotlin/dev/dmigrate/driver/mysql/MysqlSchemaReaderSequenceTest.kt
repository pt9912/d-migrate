package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

class MysqlSchemaReaderSequenceTest : FunSpec({

    val conn = mockk<Connection>(relaxUnitFun = true)
    val pool = mockk<ConnectionPool> {
        every { borrow() } returns conn
    }
    val jdbc = mockk<JdbcOperations>()
    val reader = MysqlSchemaReader(jdbcFactory = { jdbc })

    every { conn.catalog } returns "mydb"

    val stmt = mockk<Statement>(relaxUnitFun = true)
    val rs = mockk<ResultSet>(relaxUnitFun = true)
    every { conn.createStatement() } returns stmt
    every { stmt.executeQuery("SELECT @@lower_case_table_names") } returns rs
    every { rs.next() } returns true
    every { rs.getInt(1) } returns 0

    fun stubEmptyDefaults() {
        every { jdbc.queryList(match { it.contains("information_schema.tables") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.views") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("VIEW_ROUTINE_USAGE") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'FUNCTION'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("routine_type = 'PROCEDURE'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.triggers") }, any()) } returns emptyList()
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

    fun isSupportShapeQuery(sql: String): Boolean =
        "information_schema.columns" in sql &&
            "table_name = ?" in sql &&
            "ordinal_position" !in sql

    test("schema without dmg_sequences is compatible non-sequence case") {
        stubEmptyDefaults()
        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)
        result.notes.none { it.code == "W116" } shouldBe true
    }

    test("BASELINE: non-sequence single-table schema produces identical result with support scan") {
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

        result.schema.tables.keys shouldBe setOf("users")
        result.schema.tables["users"]!!.columns.keys shouldBe setOf("id", "name")
        result.schema.tables["users"]!!.primaryKey shouldBe listOf("id")
        result.schema.views.size shouldBe 0
        result.schema.functions.size shouldBe 0
        result.schema.procedures.size shouldBe 0
        result.schema.triggers.size shouldBe 0
        result.schema.sequences.size shouldBe 0

        result.notes.none { it.code == "W116" } shouldBe true
        result.notes.none { it.code.startsWith("W1") && it.code != "W100" && it.code != "W102" && it.code != "W103" } shouldBe true

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
        every {
            jdbc.queryList(match(::isSupportShapeQuery), any(), any())
        } returns listOf(
            mapOf("column_name" to "managed_by", "data_type" to "varchar"),
            mapOf("column_name" to "format_version", "data_type" to "varchar"),
            mapOf("column_name" to "name", "data_type" to "varchar"),
            mapOf("column_name" to "next_value", "data_type" to "varchar"),
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

    test("PF11: scanSequenceSupport returns AVAILABLE when dmg_sequences has extra columns") {
        stubEmptyDefaults()
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns
            mapOf("table_name" to "dmg_sequences")
        every {
            jdbc.queryList(match(::isSupportShapeQuery), any(), any())
        } returns listOf(
            mapOf("column_name" to "managed_by", "data_type" to "varchar"),
            mapOf("column_name" to "format_version", "data_type" to "varchar"),
            mapOf("column_name" to "name", "data_type" to "varchar"),
            mapOf("column_name" to "next_value", "data_type" to "bigint"),
            mapOf("column_name" to "increment_by", "data_type" to "bigint"),
            mapOf("column_name" to "min_value", "data_type" to "bigint"),
            mapOf("column_name" to "max_value", "data_type" to "bigint"),
            mapOf("column_name" to "cycle_enabled", "data_type" to "tinyint"),
            mapOf("column_name" to "cache_size", "data_type" to "int"),
            mapOf("column_name" to "description", "data_type" to "varchar"),
            mapOf("column_name" to "created_at", "data_type" to "timestamp"),
        )
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns emptyList()
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns null
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns emptyList()
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)
        snapshot.supportTableState shouldBe SupportTableState.AVAILABLE
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
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns
            mapOf("table_name" to "dmg_sequences")
        every { jdbc.queryList(match { "information_schema.columns" in it && "data_type" !in it }, any(), any()) } returns
            listOf("managed_by", "format_version", "name", "next_value", "increment_by",
                "min_value", "max_value", "cycle_enabled", "cache_size").map { mapOf("column_name" to it) }
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "seq1", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns null
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns emptyList()

        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)

        snapshot.scope.schemaName shouldBe "mydb"
        snapshot.supportTableState shouldBe SupportTableState.AVAILABLE
        snapshot.sequenceRows shouldHaveSize 1

        val otherScope = ReverseScope(catalogName = "other_db", schemaName = "other_db")
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns null
        val otherSnapshot = reader.scanSequenceSupport(jdbc, "other_db", otherScope)
        otherSnapshot.supportTableState shouldBe SupportTableState.MISSING
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
        every { jdbc.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns listOf(
            mapOf("index_name" to "fk_user", "column_name" to "user_id",
                "non_unique" to 1, "seq_in_index" to 1, "index_type" to "BTREE"),
        )

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)

        val table = result.schema.tables["orders"]!!
        table.indices.shouldBeEmpty()
        val userIdCol = table.columns["user_id"]!!
        userIdCol.references.shouldNotBeNull()
        userIdCol.references!!.table shouldBe "users"
    }
})
