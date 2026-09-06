package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnGeneration
import dev.dmigrate.core.model.DefaultValue
import dev.dmigrate.core.model.DependencyProjectionStatus
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
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

class OracleSchemaReaderTest : FunSpec({

    fun rig(jdbc: JdbcOperations): Pair<OracleSchemaReader, ConnectionPool> {
        val conn = mockk<Connection>(relaxUnitFun = true)
        val pool = mockk<ConnectionPool> {
            every { borrow() } returns JdbcDatabaseConnection(conn)
        }
        return OracleSchemaReader(jdbcFactory = { jdbc }) to pool
    }

    fun stubEmptyDefaults(jdbc: JdbcOperations) {
        every { jdbc.querySingle(match { it.contains("SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA')") }) } returns
            mapOf("schema_name" to "APP")
        every { jdbc.queryList(match { it.contains("FROM all_tables") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM all_sequences") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM all_views") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM all_dependencies") }, any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM all_objects") }, any()) } returns emptyList()
    }

    fun stubTableQueries(jdbc: JdbcOperations) {
        every { jdbc.queryList(match { it.contains("FROM all_tab_columns c") }, any(), any()) } returns emptyList()
        every {
            jdbc.queryList(match { it.contains("JOIN all_cons_columns cc") && it.contains("constraint_type = 'P'") }, any(), any())
        } returns emptyList()
        every { jdbc.queryList(match { it.contains("constraint_type = 'R'") }, any(), any()) } returns emptyList()
        every {
            jdbc.queryList(match { it.contains("SELECT index_name") && it.contains("constraint_type = 'P'") }, any(), any())
        } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM all_indexes i") }, any(), any()) } returns emptyList()
        every { jdbc.queryList(match { it.contains("constraint_type = 'C'") }, any(), any()) } returns emptyList()
    }

    test("empty schema yields reverse-scoped name and empty maps") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        result.schema.name shouldBe "__dmigrate_reverse__:oracle:schema=APP"
        result.schema.version shouldBe "0.0.0-reverse"
        result.schema.tables.shouldBeEmptyMap()
        result.schema.views.shouldBeEmptyMap()
        result.schema.sequences.shouldBeEmptyMap()
        result.notes.shouldBeEmpty()
    }

    test("single table with identity pk, fk, unique and non-unique index, check constraint") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        stubTableQueries(jdbc)
        every { jdbc.queryList(match { it.contains("FROM all_tables") }, "APP") } returns
            listOf(mapOf("table_name" to "ORDERS"))
        every { jdbc.queryList(match { it.contains("FROM all_tab_columns c") }, "APP", "ORDERS") } returns listOf(
            mapOf(
                "column_name" to "ID", "data_type" to "NUMBER", "data_length" to 22,
                "data_precision" to 10, "data_scale" to null, "nullable" to "N",
                "column_id" to 1, "data_default" to null,
                "identity_generation" to "ALWAYS", "identity_sequence" to "ISEQ\$\$_1",
            ),
            mapOf(
                "column_name" to "CUSTOMER", "data_type" to "VARCHAR2", "data_length" to 100,
                "data_precision" to null, "data_scale" to null, "nullable" to "N",
                "column_id" to 2, "data_default" to null,
                "identity_generation" to null, "identity_sequence" to null,
            ),
            mapOf(
                "column_name" to "EMAIL", "data_type" to "VARCHAR2", "data_length" to 254,
                "data_precision" to null, "data_scale" to null, "nullable" to "Y",
                "column_id" to 3, "data_default" to null,
                "identity_generation" to null, "identity_sequence" to null,
            ),
        )
        every {
            jdbc.queryList(match { it.contains("JOIN all_cons_columns cc") && it.contains("constraint_type = 'P'") }, "APP", "ORDERS")
        } returns listOf(mapOf("column_name" to "ID"))
        every { jdbc.queryList(match { it.contains("constraint_type = 'R'") }, "APP", "ORDERS") } returns listOf(
            mapOf(
                "constraint_name" to "FK_ORDERS_CUSTOMER", "column_name" to "CUSTOMER", "position" to 1,
                "referenced_table" to "CUSTOMERS", "referenced_column" to "NAME", "delete_rule" to "CASCADE",
            ),
        )
        every {
            jdbc.queryList(match { it.contains("SELECT index_name") && it.contains("constraint_type = 'P'") }, "APP", "ORDERS")
        } returns emptyList()
        every { jdbc.queryList(match { it.contains("FROM all_indexes i") }, "APP", "ORDERS") } returns listOf(
            mapOf(
                "index_name" to "UX_EMAIL", "uniqueness" to "UNIQUE",
                "column_name" to "EMAIL", "column_position" to 1, "descend" to "ASC",
            ),
            mapOf(
                "index_name" to "IX_CUSTOMER", "uniqueness" to "NONUNIQUE",
                "column_name" to "CUSTOMER", "column_position" to 1, "descend" to "ASC",
            ),
        )
        every { jdbc.queryList(match { it.contains("constraint_type = 'C'") }, "APP", "ORDERS") } returns
            listOf(mapOf("constraint_name" to "CK_EMAIL", "search_condition_vc" to "\"EMAIL\" LIKE '%@%'"))

        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        val table = result.schema.tables.getValue("ORDERS")

        table.primaryKey shouldBe listOf("ID")
        val id = table.columns.getValue("ID")
        id.generation shouldBe ColumnGeneration.Identity(mode = IdentityMode.ALWAYS, sequenceName = "ISEQ\$\$_1")
        id.required shouldBe false
        id.unique shouldBe false
        id.default.shouldBeNull()

        val customer = table.columns.getValue("CUSTOMER")
        customer.type shouldBe NeutralType.Text(100)
        customer.required shouldBe true

        val email = table.columns.getValue("EMAIL")
        // Der einspaltige Unique-Index ist auf column.unique gehoben.
        email.unique shouldBe true

        val fk = table.constraints.first { it.name == "FK_ORDERS_CUSTOMER" }
        fk.references!!.table shouldBe "CUSTOMERS"
        fk.columns shouldBe listOf("CUSTOMER")

        table.constraints.first { it.name == "CK_EMAIL" }.expression shouldBe "\"EMAIL\" LIKE '%@%'"

        // Nur der nicht-eindeutige Index bleibt als eigene Index-Definition stehen.
        table.indices shouldHaveSize 1
        table.indices[0].name shouldBe "IX_CUSTOMER"
        table.indices[0].type shouldBe IndexType.BTREE
        result.notes.shouldBeEmpty()
    }

    test("sequences fall back to LAST_NUMBER and emit an R345 info note per sequence") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM all_sequences") }, "APP") } returns listOf(
            mapOf(
                "sequence_name" to "ORDER_SEQ", "last_number" to 101L, "increment_by" to 1L,
                "min_value" to 1L, "max_value" to Long.MAX_VALUE, "cycle_flag" to "N", "cache_size" to 20,
            ),
        )
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        val seq = result.schema.sequences.getValue("ORDER_SEQ")
        seq.start shouldBe 101L
        seq.increment shouldBe 1L
        val note = result.notes.single()
        note.code shouldBe "R345"
        note.severity shouldBe SchemaReadSeverity.INFO
        note.objectName shouldBe "ORDER_SEQ"
    }

    /**
     * Oracle fuehrt die Sequenz hinter einer IDENTITY-Spalte in
     * `ALL_SEQUENCES` wie jede andere. Sie ist aber kein deklariertes
     * Objekt -- ungefiltert traegt jedes reverse-gelesene Schema mit
     * IDENTITY-Spalte eine Sequenz, die im Soll niemals steht, und
     * `schema migrate` plante ein `DROP SEQUENCE`, das Oracle ablehnt
     * (`ORA-32793`). Live beim Migrate-Round-Trip aufgefallen.
     */
    test("the sequence behind an IDENTITY column is not read as a schema sequence") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        val captured = mutableListOf<String>()
        every { jdbc.queryList(match { it.contains("FROM all_sequences") }, "APP") } answers {
            captured += firstArg<String>()
            listOf(
                mapOf(
                    "sequence_name" to "ORDER_SEQ", "last_number" to 7L, "increment_by" to 1L,
                    "min_value" to 1L, "max_value" to 9999L, "cycle_flag" to "N", "cache_size" to 20,
                ),
            )
        }
        val (reader, pool) = rig(jdbc)
        reader.read(pool).schema.sequences.keys shouldBe setOf("ORDER_SEQ")

        // Der Ausschluss steht in der Abfrage, nicht in einer
        // Nachfilterung -- sonst laege er im Ergebnis des Stubs.
        captured.single() shouldContain "all_tab_identity_cols"
        captured.single() shouldContain "NOT EXISTS"
    }

    test("views are read with the raw select text; includeViews=false skips them") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM all_views") }, "APP") } returns listOf(
            mapOf("view_name" to "V_OPEN", "text" to "SELECT 1 AS ONE FROM DUAL"),
        )
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        val view = result.schema.views.getValue("V_OPEN")
        view.query shouldBe "SELECT 1 AS ONE FROM DUAL"
        view.sourceDialect shouldBe "oracle"

        val (reader2, pool2) = rig(jdbc)
        reader2.read(pool2, SchemaReadOptions(includeViews = false)).schema.views.shouldBeEmptyMap()
    }

    /**
     * Die drei Faelle, die `ALL_DEPENDENCIES` je View liefern kann, und
     * warum sie sich unterscheiden muessen: gemessen traegt JEDE View
     * mindestens eine Zeile (die ueber `dual` verweist auf `PUBLIC.DUAL`),
     * also heisst „gar keine Zeile" fehlende Sichtbarkeit und nicht
     * „haengt von nichts ab".
     */
    test("view dependencies come from ALL_DEPENDENCIES, filtered to the own schema") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM all_views") }, "APP") } returns listOf(
            mapOf("view_name" to "V_JOIN", "text" to "SELECT 1 FROM ORDERS, ITEMS"),
        )
        every { jdbc.queryList(match { it.contains("FROM all_dependencies") }, "APP") } returns listOf(
            mapOf(
                "name" to "V_JOIN", "referenced_owner" to "APP",
                "referenced_name" to "ORDERS", "referenced_type" to "TABLE",
            ),
            mapOf(
                "name" to "V_JOIN", "referenced_owner" to "APP",
                "referenced_name" to "V_BASE", "referenced_type" to "VIEW",
            ),
            // Schemafremd -- faellt raus, belegt aber Sichtbarkeit.
            mapOf(
                "name" to "V_JOIN", "referenced_owner" to "PUBLIC",
                "referenced_name" to "DUAL", "referenced_type" to "SYNONYM",
            ),
        )
        val (reader, pool) = rig(jdbc)
        val deps = reader.read(pool).schema.views.getValue("V_JOIN").dependencies!!
        deps.tables shouldBe listOf("ORDERS")
        deps.views shouldBe listOf("V_BASE")
        deps.projectionComplete shouldBe true
        deps.tableProjectionStatus shouldBe DependencyProjectionStatus.COMPLETE
        deps.projectionSources shouldBe listOf("ALL_DEPENDENCIES")
        // Oracle hat keine spaltengenaue Quelle -- das laesst den
        // VIEW_DEPENDS_ON_TABLE_LACKS_COLUMN_DEPS-Waechter greifen.
        deps.columns.shouldBeEmptyMap()
    }

    test("a view with no ALL_DEPENDENCIES row at all reports an incomplete projection") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM all_views") }, "APP") } returns listOf(
            mapOf("view_name" to "V_HIDDEN", "text" to "SELECT 1 FROM SOMEWHERE"),
        )
        every { jdbc.queryList(match { it.contains("FROM all_dependencies") }, "APP") } returns emptyList()
        val (reader, pool) = rig(jdbc)
        val deps = reader.read(pool).schema.views.getValue("V_HIDDEN").dependencies!!
        // Nicht als "keine Abhaengigkeiten" lesen -- das waere die
        // gefaehrliche Deutung. Unbrauchbar melden, damit der Planer
        // `ReplaceView` blockt statt zu raten.
        deps.projectionComplete shouldBe false
        deps.tableProjectionStatus shouldBe DependencyProjectionStatus.INCOMPLETE_PRIVILEGE
        deps.dependencyProjectionUsable() shouldBe false
    }

    test("a view reaching its table through a synonym is not reported as verified empty") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM all_views") }, "APP") } returns listOf(
            mapOf("view_name" to "V_SYN", "text" to "SELECT 1 FROM MY_SYN"),
        )
        every { jdbc.queryList(match { it.contains("FROM all_dependencies") }, "APP") } returns listOf(
            // Eigenes Schema, aber weder TABLE noch VIEW -- faellt aus
            // `tables`/`views` heraus, ohne dass die Sicht deshalb nichts
            // referenziert.
            mapOf(
                "name" to "V_SYN", "referenced_owner" to "APP",
                "referenced_name" to "MY_SYN", "referenced_type" to "SYNONYM",
            ),
        )
        val (reader, pool) = rig(jdbc)
        val deps = reader.read(pool).schema.views.getValue("V_SYN").dependencies!!
        deps.tables.shouldBeEmpty()
        // Als EMPTY_VERIFIED gemeldet faende der Reprojector beim Rename
        // nichts und liesse die Sicht still invalid zurueck.
        deps.tableProjectionStatus shouldBe DependencyProjectionStatus.UNKNOWN
        deps.dependencyProjectionUsable() shouldBe false
    }

    test("a view referencing only foreign objects reports an empty but verified projection") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM all_views") }, "APP") } returns listOf(
            mapOf("view_name" to "V_CONST", "text" to "SELECT 1 FROM dual"),
        )
        every { jdbc.queryList(match { it.contains("FROM all_dependencies") }, "APP") } returns listOf(
            mapOf(
                "name" to "V_CONST", "referenced_owner" to "PUBLIC",
                "referenced_name" to "DUAL", "referenced_type" to "SYNONYM",
            ),
        )
        val (reader, pool) = rig(jdbc)
        val deps = reader.read(pool).schema.views.getValue("V_CONST").dependencies!!
        deps.tables.shouldBeEmpty()
        deps.tableProjectionStatus shouldBe DependencyProjectionStatus.EMPTY_VERIFIED
        deps.projectionComplete shouldBe true
        deps.dependencyProjectionUsable() shouldBe true
    }

    test("unread routines, functions, triggers and packages surface as skippedObjects plus R342 notes") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM all_objects") }, "APP") } returns listOf(
            mapOf("object_type" to "PROCEDURE", "object_name" to "P_DO"),
            mapOf("object_type" to "FUNCTION", "object_name" to "F_CALC"),
            mapOf("object_type" to "TRIGGER", "object_name" to "TRG_AUDIT"),
            mapOf("object_type" to "PACKAGE", "object_name" to "PKG_UTIL"),
        )
        val (reader, pool) = rig(jdbc)
        val result = reader.read(pool)
        result.skippedObjects.map { it.type to it.name } shouldBe listOf(
            "procedure" to "P_DO", "function" to "F_CALC", "trigger" to "TRG_AUDIT", "procedure" to "PKG_UTIL",
        )
        result.skippedObjects.forEach { it.code shouldBe "R342" }
        result.notes shouldHaveSize 3
        result.notes.forEach {
            it.code shouldBe "R342"
            it.severity shouldBe SchemaReadSeverity.WARNING
        }
    }

    test("unread-object notes honour the read options") {
        val jdbc = mockk<JdbcOperations>()
        stubEmptyDefaults(jdbc)
        every { jdbc.queryList(match { it.contains("FROM all_objects") }, "APP") } returns listOf(
            mapOf("object_type" to "PROCEDURE", "object_name" to "P_DO"),
            mapOf("object_type" to "TRIGGER", "object_name" to "TRG_AUDIT"),
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
        OracleDriver().schemaReader()::class.simpleName shouldBe "OracleSchemaReader"
    }
})
