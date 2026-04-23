package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

private fun isSupportShapeQuery(sql: String): Boolean =
    "information_schema.columns" in sql &&
        "table_name = ?" in sql &&
        "ordinal_position" !in sql

class MysqlSchemaReaderD2Test : FunSpec({

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

    fun stubCanonicalShape() {
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
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns
            mapOf("table_name" to "dmg_sequences")
        every {
            jdbc.queryList(match(::isSupportShapeQuery), any(), any())
        } returns listOf(
            mapOf("column_name" to "name", "data_type" to "varchar"),
            mapOf("column_name" to "value", "data_type" to "text"),
        )
        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)
        result.schema.tables shouldContainKey "dmg_sequences"
    }

    test("support objects are filtered from user schema when AVAILABLE") {
        stubEmptyDefaults()
        stubTableQueries()
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
        every { jdbc.querySingle(match { "table_name = ?" in it }, any(), any()) } returns
            mapOf("table_name" to "dmg_sequences")
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns emptyList()
        stubNoRoutinesOrTriggers()

        val opts = SchemaReadOptions(includeViews = false, includeFunctions = true,
            includeProcedures = true, includeTriggers = true)
        val result = reader.read(pool, opts)

        result.schema.tables shouldContainKey "users"
        result.schema.tables.keys shouldBe setOf("users")
    }

    test("D2: NON_CANONICAL routine produces W116 per materialized sequence") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "seq1", "next_value" to 1L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        every { jdbc.querySingle(match { "routine_name = ?" in it }, eq("mydb"), eq("dmg_nextval")) } returns
            mapOf("routine_name" to "dmg_nextval", "routine_definition" to "/* d-migrate:mysql-sequence-v1 object=nextval */",
                "data_type" to "varchar", "dtd_identifier" to "varchar(255)")
        every { jdbc.querySingle(match { "routine_name = ?" in it }, eq("mydb"), eq("dmg_setval")) } returns null
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns emptyList()

        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)
        snapshot.routineStates[MysqlSequenceNaming.NEXTVAL_ROUTINE] shouldBe SupportRoutineState.NON_CANONICAL
        val d2 = reader.materializeSupportSequences(snapshot)
        d2.sequences.keys shouldBe setOf("seq1")
        d2.notes.any { it.code == "W116" && it.objectName == "seq1" && it.hint?.contains("not canonical") == true } shouldBe true
    }
})
