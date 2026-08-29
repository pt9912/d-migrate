package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.IdentityMode
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SchemaReadSeverity
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldBeEmpty as shouldBeEmptyMap
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

class MssqlSchemaReaderTest : FunSpec({

    fun rig(jdbc: JdbcOperations): Pair<MssqlSchemaReader, ConnectionPool> {
        val conn = mockk<Connection>(relaxUnitFun = true) {
            every { catalog } returns "shopdb"
        }
        val pool = mockk<ConnectionPool> {
            every { borrow() } returns JdbcDatabaseConnection(conn)
        }
        return MssqlSchemaReader(jdbcFactory = { jdbc }) to pool
    }

    fun stubEmptyDefaults(jdbc: JdbcOperations) {
        every { jdbc.querySingle(match { it.contains("SCHEMA_NAME()") }) } returns
            mapOf("schema_name" to "dbo")
        every { jdbc.queryList(match { it.contains("FROM sys.tables t") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.sequences seq") }, any()) } returns emptyList()
        // Volltext: diese Specs decken ihn nicht ab und lesen ihn als leer.
        every {
            jdbc.queryList(match { it.contains("FROM sys.fulltext_index_columns") }, any())
        } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.views v") }, any()) } returns emptyList()
        every {
            jdbc.queryList(match { it.contains("FROM sys.objects o") && it.contains("m.definition IS NULL") }, any())
        } returns emptyList()
        // Slice 9a: gelesene Routinen und ihre Parameter — diese Specs decken
        // sie nicht ab und lesen sie als leer.
        every {
            jdbc.queryList(match { it.contains("FROM sys.objects o") && it.contains("JOIN sys.sql_modules m") &&
                    !it.contains("m.definition IS NULL") }, any())
        } returns emptyList()
        every { jdbc.queryList(match { it.contains("JOIN sys.parameters p") }, any()) } returns emptyList()
    }

    fun stubTableQueries(jdbc: JdbcOperations) {
        every { jdbc.queryList(match { it.contains("FROM sys.columns c") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("kc.type = 'PK'") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.foreign_keys fk") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.indexes i") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM sys.check_constraints cc") }, any()) } returns emptyList()
    }

    fun columnRow(
        name: String,
        type: String = "int",
        maxLength: Int = 4,
        nullable: Boolean = false,
        identity: Boolean = false,
        seed: Long? = null,
        increment: Long? = null,
        computed: Boolean = false,
        computedDefinition: String? = null,
        default: String? = null,
        ordinal: Int = 1,
    ): Map<String, Any?> = mapOf(
        "column_name" to name, "type_name" to type, "max_length" to maxLength,
        "precision" to 10, "scale" to 0, "is_nullable" to nullable,
        "is_identity" to identity, "is_computed" to computed, "column_id" to ordinal,
        "default_definition" to default, "seed_value" to seed, "increment_value" to increment,
        "computed_definition" to computedDefinition,
    )

    test("empty schema yields reverse-scoped name and empty maps") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        result.schema.name shouldBe "__dmigrate_reverse__:mssql:database=shopdb;schema=dbo"
        result.schema.version shouldBe "0.0.0-reverse"
        result.schema.tables.shouldBeEmptyMap()
        result.schema.views.shouldBeEmptyMap()
        result.schema.sequences.shouldBeEmptyMap()
        result.notes.shouldBeEmpty()
        result.mysqlServerVersion.shouldBeNull()
    }

    test("single table with identity pk, default, fk, unique and filtered index") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        stubTableQueries(jdbc)
        every { jdbc.queryList(match { it.contains("FROM sys.tables t") }, "dbo") } returns
            listOf(mapOf("table_name" to "orders", "schema_name" to "dbo"))
        every { jdbc.queryList(match { it.contains("FROM sys.columns c") }, "[dbo].[orders]") } returns listOf(
            columnRow("id", identity = true, seed = 1L, increment = 1L, ordinal = 1),
            columnRow("customer", type = "nvarchar", maxLength = 200, ordinal = 2),
            columnRow("mail", type = "nvarchar", maxLength = 508, nullable = true, ordinal = 3),
            columnRow(
                "state", type = "nvarchar", maxLength = 20, nullable = true,
                default = "(N'new')", ordinal = 4,
            ),
        )
        every { jdbc.queryList(match { it.contains("kc.type = 'PK'") }, "[dbo].[orders]") } returns
            listOf(mapOf("column_name" to "id"))
        every { jdbc.queryList(match { it.contains("FROM sys.foreign_keys fk") }, "[dbo].[orders]") } returns listOf(
            mapOf(
                "constraint_name" to "fk_orders_customer", "column_name" to "customer",
                "referenced_table" to "customers", "referenced_column" to "name",
                "delete_referential_action_desc" to "CASCADE",
                "update_referential_action_desc" to "NO_ACTION",
                "constraint_column_id" to 1,
            ),
        )
        every { jdbc.queryList(match { it.contains("FROM sys.indexes i") }, "[dbo].[orders]") } returns listOf(
            mapOf(
                "index_name" to "ux_mail", "is_unique" to true, "has_filter" to false,
                "filter_definition" to null, "column_name" to "mail",
                "key_ordinal" to 1, "is_descending_key" to false, "is_included_column" to false,
            ),
            mapOf(
                "index_name" to "ix_state_open", "is_unique" to false, "has_filter" to true,
                "filter_definition" to "([state]='open')", "column_name" to "state",
                "key_ordinal" to 1, "is_descending_key" to false, "is_included_column" to false,
            ),
        )
        every { jdbc.queryList(match { it.contains("FROM sys.check_constraints cc") }, "[dbo].[orders]") } returns
            listOf(mapOf("constraint_name" to "ck_state", "definition" to "([state]<>N'')"))

        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        val table = result.schema.tables.getValue("orders")

        table.primaryKey shouldBe listOf("id")
        val id = table.columns.getValue("id")
        id.type shouldBe NeutralType.Identifier(autoIncrement = true)
        id.required shouldBe false
        id.unique shouldBe false
        id.default.shouldBeNull()

        val customer = table.columns.getValue("customer")
        customer.type shouldBe NeutralType.Text(100)
        customer.required shouldBe true
        customer.ordinal shouldBe 2

        val mail = table.columns.getValue("mail")
        mail.type shouldBe NeutralType.Text(254)
        mail.unique shouldBe true

        table.columns.getValue("state").default shouldBe DefaultValue.StringLiteral("new")

        val fk = table.constraints.first { it.name == "fk_orders_customer" }
        fk.references!!.table shouldBe "customers"
        fk.columns shouldBe listOf("customer")

        // Der reverse-gelesene CHECK traegt neutrale Syntax, keine T-SQL-Oberflaeche:
        // der Unicode-Praefix faellt weg (der Validator laese ihn sonst als
        // Spaltenbezug, E012) und das Klammer-Quoting ebenso (sonst scheitert
        // jedes andere Ziel an der Syntax).
        table.constraints.first { it.name == "ck_state" }.expression shouldBe "state<>''"

        // Der einspaltige Unique-Index ist auf column.unique gehoben; nur der
        // gefilterte Index bleibt als Index-Definition stehen.
        table.indices shouldHaveSize 1
        table.indices[0].name shouldBe "ix_state_open"
        table.indices[0].where shouldBe "([state]='open')"
        table.indices[0].type shouldBe IndexType.BTREE
        result.notes.shouldBeEmpty()
    }

    test("bigint identity carries ALWAYS generation; non-default seed emits R340") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        stubTableQueries(jdbc)
        every { jdbc.queryList(match { it.contains("FROM sys.tables t") }, "dbo") } returns
            listOf(mapOf("table_name" to "t", "schema_name" to "dbo"))
        every { jdbc.queryList(match { it.contains("FROM sys.columns c") }, "[dbo].[t]") } returns listOf(
            columnRow("id", type = "bigint", maxLength = 8, identity = true, seed = 1000L, increment = 10L),
        )
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        result.schema.tables.getValue("t").columns.getValue("id").generation shouldBe
            ColumnGeneration.Identity(mode = IdentityMode.ALWAYS)
        val note = result.notes.single()
        note.code shouldBe "R340"
        note.severity shouldBe SchemaReadSeverity.WARNING
        note.objectName shouldBe "t.id"
    }

    test("computed column suppresses default and emits R343 action_required") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        stubTableQueries(jdbc)
        every { jdbc.queryList(match { it.contains("FROM sys.tables t") }, "dbo") } returns
            listOf(mapOf("table_name" to "t", "schema_name" to "dbo"))
        every { jdbc.queryList(match { it.contains("FROM sys.columns c") }, "[dbo].[t]") } returns listOf(
            columnRow("total", type = "money", maxLength = 8, computed = true, computedDefinition = "([a]+[b])"),
        )
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        result.schema.tables.getValue("t").columns.getValue("total").default.shouldBeNull()
        val note = result.notes.single()
        note.code shouldBe "R343"
        note.severity shouldBe SchemaReadSeverity.ACTION_REQUIRED
    }

    test("index INCLUDE columns are carried into the model, not merged into the key") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        stubTableQueries(jdbc)
        every { jdbc.queryList(match { it.contains("FROM sys.tables t") }, "dbo") } returns
            listOf(mapOf("table_name" to "t", "schema_name" to "dbo"))
        every { jdbc.queryList(match { it.contains("FROM sys.columns c") }, "[dbo].[t]") } returns
            listOf(columnRow("a"), columnRow("b", ordinal = 2))
        every { jdbc.queryList(match { it.contains("FROM sys.indexes i") }, "[dbo].[t]") } returns listOf(
            mapOf(
                "index_name" to "ix_a", "is_unique" to false, "has_filter" to false,
                "filter_definition" to null, "column_name" to "a",
                "key_ordinal" to 1, "is_descending_key" to false, "is_included_column" to false,
            ),
            mapOf(
                "index_name" to "ix_a", "is_unique" to false, "has_filter" to false,
                "filter_definition" to null, "column_name" to "b",
                "key_ordinal" to 0, "is_descending_key" to false, "is_included_column" to true,
            ),
        )
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        result.notes.shouldBeEmpty()
        val index = result.schema.tables.getValue("t").indices.single()
        // Die eingeschlossene Spalte steht NEBEN dem Schluessel, nicht darin —
        // angehaengt waere aus dem abdeckenden ein zusammengesetzter Index geworden.
        index.columns.map { it.name } shouldBe listOf("a")
        index.includeColumns shouldBe listOf("b")
        index.clustered shouldBe false
    }

    test("a clustered index is read as the table's storage") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        stubTableQueries(jdbc)
        every { jdbc.queryList(match { it.contains("FROM sys.tables t") }, "dbo") } returns
            listOf(mapOf("table_name" to "t", "schema_name" to "dbo"))
        every { jdbc.queryList(match { it.contains("FROM sys.columns c") }, "[dbo].[t]") } returns
            listOf(columnRow("a"))
        every { jdbc.queryList(match { it.contains("FROM sys.indexes i") }, "[dbo].[t]") } returns listOf(
            mapOf(
                "index_name" to "ix_a", "is_unique" to false, "has_filter" to false,
                "filter_definition" to null, "type" to 1, "column_name" to "a",
                "key_ordinal" to 1, "is_descending_key" to false, "is_included_column" to false,
            ),
        )
        val (reader, pool) = rig(jdbc)
        reader.read(pool).schema.tables.getValue("t").indices.single().clustered shouldBe true
    }

    test("views are read with extracted query; includeViews=false skips them") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM sys.views v") }, "dbo") } returns listOf(
            mapOf("view_name" to "v_open", "definition" to "CREATE VIEW dbo.v_open AS SELECT 1 AS one"),
        )
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        val view = result.schema.views.getValue("v_open")
        view.query shouldBe "SELECT 1 AS one"
        view.sourceDialect shouldBe "mssql"

        val (reader2, pool2) = rig(jdbc)
        reader2.read(pool2, SchemaReadOptions(includeViews = false)).schema.views.shouldBeEmptyMap()
    }

    test("unparseable view definition falls back to raw text with R344") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM sys.views v") }, "dbo") } returns listOf(
            mapOf("view_name" to "v_broken", "definition" to "EXEC weird_module"),
        )
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        result.schema.views.getValue("v_broken").query shouldBe "EXEC weird_module"
        result.notes.single().code shouldBe "R344"
    }

    test("sequences normalise type-bound min/max to null") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM sys.sequences seq") }, "dbo") } returns listOf(
            mapOf(
                "sequence_name" to "order_seq", "type_name" to "bigint",
                "start_value" to 100L, "increment_value" to 5L,
                "minimum_value" to Long.MIN_VALUE, "maximum_value" to Long.MAX_VALUE,
                "is_cycling" to false, "is_cached" to true, "cache_size" to 50,
            ),
            mapOf(
                "sequence_name" to "bounded_seq", "type_name" to "int",
                "start_value" to 1L, "increment_value" to 1L,
                "minimum_value" to 1L, "maximum_value" to 9999L,
                "is_cycling" to true, "is_cached" to false, "cache_size" to null,
            ),
        )
        val (reader, pool) = rig(jdbc)
        val sequences = reader.read(pool).schema.sequences
        val orderSeq = sequences.getValue("order_seq")
        orderSeq.start shouldBe 100L
        orderSeq.increment shouldBe 5L
        orderSeq.minValue.shouldBeNull()
        orderSeq.maxValue.shouldBeNull()
        orderSeq.cache shouldBe 50
        val boundedSeq = sequences.getValue("bounded_seq")
        boundedSeq.minValue shouldBe 1L
        boundedSeq.maxValue shouldBe 9999L
        boundedSeq.cycle shouldBe true
    }

    // Slice 9a: Signatur aus `sys.parameters`, Rumpf aus dem Definitionstext.
    test("routines are read with parameters from the catalog and the body cut at the top-level AS") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every {
            jdbc.queryList(match { it.contains("FROM sys.objects o") && it.contains("JOIN sys.sql_modules m") &&
                    !it.contains("m.definition IS NULL") }, "dbo")
        } returns listOf(
            mapOf(
                "object_type" to "FN", "object_name" to "fn_double",
                "definition" to "CREATE FUNCTION dbo.fn_double (@x INT) RETURNS INT AS BEGIN RETURN @x * 2 END",
                "parent_name" to null, "is_insert" to 0, "is_update" to 0,
                "is_delete" to 0, "is_instead_of" to 0,
            ),
            mapOf(
                "object_type" to "TR", "object_name" to "trg_audit",
                "definition" to "CREATE TRIGGER trg_audit ON t AFTER INSERT AS SELECT 1",
                "parent_name" to "t", "is_insert" to 1, "is_update" to 0,
                "is_delete" to 0, "is_instead_of" to 0,
            ),
        )
        every { jdbc.queryList(match { it.contains("JOIN sys.parameters p") }, "dbo") } returns listOf(
            mapOf(
                "routine_name" to "fn_double", "param_name" to "", "type_name" to "int",
                "is_output" to true, "parameter_id" to 0,
            ),
            mapOf(
                "routine_name" to "fn_double", "param_name" to "@x", "type_name" to "int",
                "is_output" to false, "parameter_id" to 1,
            ),
        )

        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)

        // Kanonischer Key aus Name und Signatur, mit neutralen Typnamen —
        // `int` steht dort nicht, sonst truege dieselbe Funktion je nach
        // Quell-Dialekt einen anderen Key.
        val fn = result.schema.functions.getValue("fn_double(in:integer)")
        fn.body shouldBe "BEGIN RETURN @x * 2 END"
        fn.parameters.map { it.name } shouldBe listOf("x")
        fn.parameters.map { it.type } shouldBe listOf("integer")
        fn.returns?.type shouldBe "integer"
        fn.sourceDialect shouldBe "mssql"

        val trg = result.schema.triggers.getValue("t::trg_audit")
        trg.body shouldBe "SELECT 1"
        trg.table shouldBe "t"
    }

    // Eine mehrteilige Tabellenfunktion deklariert `RETURNS @r TABLE (...)` vor
    // dem `AS`; das neutrale Modell traegt diese Form nicht.
    test("a multi-statement table-valued function is skipped with R350, an inline one returns table") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every {
            jdbc.queryList(match { it.contains("FROM sys.objects o") && it.contains("JOIN sys.sql_modules m") &&
                    !it.contains("m.definition IS NULL") }, "dbo")
        } returns listOf(
            mapOf(
                "object_type" to "TF", "object_name" to "tf_rows",
                "definition" to "CREATE FUNCTION dbo.tf_rows() RETURNS @r TABLE (id INT) AS BEGIN RETURN END",
                "parent_name" to null, "is_insert" to 0, "is_update" to 0,
                "is_delete" to 0, "is_instead_of" to 0,
            ),
            mapOf(
                "object_type" to "IF", "object_name" to "if_rows",
                "definition" to "CREATE FUNCTION dbo.if_rows() RETURNS TABLE AS RETURN (SELECT 1 AS id)",
                "parent_name" to null, "is_insert" to 0, "is_update" to 0,
                "is_delete" to 0, "is_instead_of" to 0,
            ),
        )
        every { jdbc.queryList(match { it.contains("JOIN sys.parameters p") }, "dbo") } returns emptyList()

        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)

        result.schema.functions.keys shouldBe setOf("if_rows()")
        result.schema.functions.getValue("if_rows()").returns?.type shouldBe "table"
        result.skippedObjects.single { it.name == "tf_rows" }.code shouldBe "R350"
        result.notes.single { it.code == "R350" }.objectName shouldBe "tf_rows"
    }

    // Lieber melden als raten: ohne oberstes `AS` laesst sich der Rumpf nicht
    // von der Signatur trennen.
    test("a definition without a top-level AS is skipped with R349") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every {
            jdbc.queryList(match { it.contains("FROM sys.objects o") && it.contains("JOIN sys.sql_modules m") &&
                    !it.contains("m.definition IS NULL") }, "dbo")
        } returns listOf(
            mapOf(
                "object_type" to "P", "object_name" to "usp_odd",
                "definition" to "CREATE PROCEDURE usp_odd", "parent_name" to null,
                "is_insert" to 0, "is_update" to 0, "is_delete" to 0, "is_instead_of" to 0,
            ),
        )

        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)

        result.schema.procedures shouldBe emptyMap()
        result.skippedObjects.single { it.name == "usp_odd" }.code shouldBe "R349"
        result.notes.any { it.code == "R349" } shouldBe true
    }

    test("unread routines and triggers surface as skippedObjects plus R342 notes") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every {
            jdbc.queryList(match { it.contains("FROM sys.objects o") && it.contains("m.definition IS NULL") }, "dbo")
        } returns listOf(
            mapOf("object_type" to "P ", "object_name" to "usp_do"),
            mapOf("object_type" to "PC", "object_name" to "usp_clr"),
            mapOf("object_type" to "FN", "object_name" to "fn_calc"),
            mapOf("object_type" to "TA", "object_name" to "trg_clr"),
            mapOf("object_type" to "TR", "object_name" to "trg_audit"),
        )
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        result.skippedObjects.map { it.type to it.name } shouldBe listOf(
            "procedure" to "usp_do", "procedure" to "usp_clr", "function" to "fn_calc",
            "trigger" to "trg_clr", "trigger" to "trg_audit",
        )
        result.notes shouldHaveSize 3
        result.notes.forEach {
            it.code shouldBe "R342"
            it.severity shouldBe SchemaReadSeverity.WARNING
        }
    }

    test("unread-object notes honour the read options") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every {
            jdbc.queryList(match { it.contains("FROM sys.objects o") && it.contains("m.definition IS NULL") }, "dbo")
        } returns listOf(
            mapOf("object_type" to "P ", "object_name" to "usp_do"),
            mapOf("object_type" to "TR", "object_name" to "trg_audit"),
        )
        val (reader, pool) = rig(jdbc)
        val result = reader.read(
            pool,
            SchemaReadOptions(includeProcedures = false, includeTriggers = false),
        )
        result.skippedObjects.shouldBeEmpty()
        result.notes.shouldBeEmpty()
    }

    test("driver exposes this reader") {
        MssqlDriver().schemaReader()::class.simpleName shouldBe "MssqlSchemaReader"
    }
})
