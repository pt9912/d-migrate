package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.*
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Statement

private fun isSupportShapeQuery(sql: String): Boolean =
    "information_schema.columns" in sql &&
        "table_name = ?" in sql &&
        "ordinal_position" !in sql

private fun isOrderedColumnQuery(sql: String): Boolean =
    sql.contains("information_schema.columns") && "ordinal_position" in sql

private fun supportTriggerStatement(markerSequence: String, callSequence: String): String =
    "BEGIN " +
        "/* d-migrate:mysql-sequence-v1 object=sequence-trigger " +
        "sequence=$markerSequence table=orders column=invoice_number */ " +
        "IF NEW.`invoice_number` IS NULL THEN " +
        "SET NEW.`invoice_number` = `dmg_nextval`('$callSequence'); END IF; END"

class MysqlSchemaReaderD3Test : FunSpec({

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

    fun makeSnapshot(
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
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger),
        )
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                confirmedTriggerName, SupportTriggerState.CONFIRMED, "orders", "invoice_number", "invoice_seq",
            ),
        ))
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
        col.default shouldBe null
        d3.confirmedTriggerNames.shouldBeEmpty()
        d3.notes.any { it.code == "W116" && "orders.invoice_number" in it.objectName } shouldBe true
    }

    test("D3: trigger referencing non-materialized sequence produces no SequenceNextVal and W116") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger),
        )
        val snapshot = makeSnapshot(
            triggerAssessments = listOf(
                MysqlMetadataQueries.SupportTriggerAssessment(
                    confirmedTriggerName, SupportTriggerState.CONFIRMED, "orders", "invoice_number", "ghost_seq",
                ),
            ),
        )
        val d3 = reader.materializeSequenceDefaults(snapshot, emptyMap(), tables)
        val col = d3.enrichedTables["orders"]!!.columns["invoice_number"]!!
        col.default shouldBe null
        d3.notes.any { it.code == "W116" && "orders.invoice_number" in it.objectName } shouldBe true
    }

    test("D3: conflicting SequenceNextVal with different sequence produces W116") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger,
                default = DefaultValue.SequenceNextVal("other_seq")),
        )
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                confirmedTriggerName, SupportTriggerState.CONFIRMED, "orders", "invoice_number", "invoice_seq",
            ),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        val col = d3.enrichedTables["orders"]!!.columns["invoice_number"]!!
        col.default shouldBe DefaultValue.SequenceNextVal("other_seq")
        d3.notes.any { it.code == "W116" && "conflicting" in (it.hint ?: "") } shouldBe true
    }

    test("D3: identical SequenceNextVal default is compatible (no conflict)") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger,
                default = DefaultValue.SequenceNextVal("invoice_seq")),
        )
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                confirmedTriggerName, SupportTriggerState.CONFIRMED, "orders", "invoice_number", "invoice_seq",
            ),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        val col = d3.enrichedTables["orders"]!!.columns["invoice_number"]!!
        col.default shouldBe DefaultValue.SequenceNextVal("invoice_seq")
        d3.notes.none { it.code == "W116" } shouldBe true
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
        val col = d3.enrichedTables["orders"]!!.columns["invoice_number"]!!
        col.default shouldBe DefaultValue.NumberLiteral(0)
        d3.notes.any { it.code == "W116" && "conflicting" in (it.hint ?: "") } shouldBe true
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

    test("D3: marker with mismatched sequence field produces NON_CANONICAL") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "invoice_seq", "next_value" to 1000L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns listOf(
            mapOf("trigger_name" to confirmedTriggerName,
                "action_timing" to "BEFORE", "event_manipulation" to "INSERT",
                "event_object_table" to "orders",
                "action_statement" to supportTriggerStatement("OTHER", "invoice_seq"),
            ),
        )
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)
        snapshot.triggerAssessments[0].state shouldBe SupportTriggerState.NON_CANONICAL
    }

    test("D3: backtick-quoted same-schema sequence reference is in-scope") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "invoice_seq", "next_value" to 1000L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        stubNoRoutinesOrTriggers()
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns listOf(
            mapOf("trigger_name" to confirmedTriggerName,
                "action_timing" to "BEFORE", "event_manipulation" to "INSERT",
                "event_object_table" to "orders",
                "action_statement" to supportTriggerStatement(
                    "invoice_seq",
                    "`mydb`.`invoice_seq`",
                ),
            ),
        )
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)
        snapshot.triggerAssessments[0].state shouldBe SupportTriggerState.CONFIRMED
        snapshot.triggerAssessments[0].sequenceName shouldBe "invoice_seq"
    }

    test("D3: cross-schema qualified sequence reference produces NON_CANONICAL") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns emptyList()
        stubNoRoutinesOrTriggers()
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns listOf(
            mapOf("trigger_name" to confirmedTriggerName,
                "action_timing" to "BEFORE", "event_manipulation" to "INSERT",
                "event_object_table" to "orders",
                "action_statement" to supportTriggerStatement(
                    "invoice_seq",
                    "other_db.invoice_seq",
                ),
            ),
        )
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = reader.scanSequenceSupport(jdbc, "mydb", scope)
        snapshot.triggerAssessments[0].state shouldBe SupportTriggerState.NON_CANONICAL
    }

    test("D3: includeTriggers=false via full read() still materializes SequenceNextVal") {
        stubEmptyDefaults()
        stubCanonicalShape()
        every { jdbc.queryList(match { it.contains("information_schema.tables") && "table_type" in it }, any()) } returns listOf(
            mapOf("table_name" to "orders", "table_schema" to "mydb", "table_type" to "BASE TABLE"),
        )
        every {
            jdbc.queryList(match(::isOrderedColumnQuery), any(), any())
        } returns listOf(
            mapOf("column_name" to "id", "data_type" to "int", "column_type" to "int(11)",
                "is_nullable" to "NO", "column_default" to null, "ordinal_position" to 1,
                "extra" to "auto_increment", "character_maximum_length" to null,
                "numeric_precision" to 10, "numeric_scale" to 0),
            mapOf("column_name" to "invoice_number", "data_type" to "bigint", "column_type" to "bigint(20)",
                "is_nullable" to "YES", "column_default" to null, "ordinal_position" to 2,
                "extra" to "", "character_maximum_length" to null,
                "numeric_precision" to 19, "numeric_scale" to 0),
        )
        every { jdbc.queryList(match { it.contains("constraint_name = 'PRIMARY'") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("referential_constraints") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("information_schema.statistics") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("CHECK") }, any(), any()) } returns emptyList()
        every { jdbc.querySingle(match { it.contains("engine") }, any(), any()) } returns null
        every { jdbc.queryList(match { "dmg_sequences" in it && "managed_by" in it }) } returns listOf(
            mapOf("managed_by" to "d-migrate", "format_version" to "mysql-sequence-v1",
                "name" to "invoice_seq", "next_value" to 1000L, "increment_by" to 1L,
                "min_value" to null, "max_value" to null, "cycle_enabled" to 0, "cache_size" to null),
        )
        every { jdbc.querySingle(match { "routine_name = ?" in it }, any(), any()) } returns null
        every { jdbc.queryList(match { "trigger_name LIKE" in it }, any()) } returns listOf(
            mapOf("trigger_name" to confirmedTriggerName,
                "action_timing" to "BEFORE", "event_manipulation" to "INSERT",
                "event_object_table" to "orders",
                "action_statement" to supportTriggerStatement(
                    "invoice_seq",
                    "invoice_seq",
                ),
            ),
        )
        val opts = SchemaReadOptions(includeViews = false, includeFunctions = false,
            includeProcedures = false, includeTriggers = false)
        val result = reader.read(pool, opts)
        result.schema.tables["orders"]!!.columns["invoice_number"]!!.default shouldBe DefaultValue.SequenceNextVal("invoice_seq")
        result.schema.triggers.size shouldBe 0
        result.schema.sequences.containsKey("invoice_seq") shouldBe true
    }

    test("D3: MISSING_MARKER without verankerbare Spalte produces no W116") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
        )
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                "dmg_seq_orders_unknown_abc1234567_bi", SupportTriggerState.MISSING_MARKER, "orders", null, null,
            ),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        d3.notes.shouldBeEmpty()
    }

    test("D3: NOT_ACCESSIBLE trigger scan preserves D2 sequences with W116") {
        val scope = ReverseScope(catalogName = "mydb", schemaName = "mydb")
        val snapshot = MysqlSequenceSupportSnapshot(
            scope = scope,
            supportTableState = SupportTableState.AVAILABLE,
            sequenceRows = emptyList(),
            ambiguousKeys = emptySet(),
            routineStates = emptyMap(),
            triggerStates = emptyMap(),
            triggerAssessments = emptyList(),
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
        d3.enrichedTables["orders"]!!.columns["invoice_number"]!!.default shouldBe null
        d3.confirmedTriggerNames.shouldBeEmpty()
        d3.notes.any { it.code == "W116" && "invoice_seq" in it.objectName } shouldBe true
    }

    test("D3: confirmed trigger with wrong forward-verified name is not confirmed") {
        val tables = makeTable(
            "id" to ColumnDefinition(type = NeutralType.Identifier(autoIncrement = true)),
            "invoice_number" to ColumnDefinition(type = NeutralType.BigInteger),
        )
        val wrongName = "dmg_seq_orders_invoicenumber_0000000000_bi"
        val snapshot = makeSnapshot(triggerAssessments = listOf(
            MysqlMetadataQueries.SupportTriggerAssessment(
                wrongName, SupportTriggerState.CONFIRMED, "orders", "invoice_number", "invoice_seq",
            ),
        ))
        val d3 = reader.materializeSequenceDefaults(snapshot, mapOf("invoice_seq" to SequenceDefinition(start = 1000)), tables)
        d3.enrichedTables["orders"]!!.columns["invoice_number"]!!.default shouldBe null
        d3.confirmedTriggerNames.shouldBeEmpty()
        d3.notes.any { it.code == "W116" } shouldBe true
    }
})
