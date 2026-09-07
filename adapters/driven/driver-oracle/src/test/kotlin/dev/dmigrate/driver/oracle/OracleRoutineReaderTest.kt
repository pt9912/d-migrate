package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.SchemaReadNote
import dev.dmigrate.driver.SchemaReadOptions
import dev.dmigrate.driver.SkippedObject
import dev.dmigrate.driver.metadata.JdbcOperations
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty as shouldBeEmptyMap
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk

/**
 * Der Zusammenbau aus den Katalogzeilen. Die Zeilen hier sind so geformt, wie
 * der Server sie liefert — einschliesslich der Eigenheiten, die beim Messen
 * auffielen.
 */
class OracleRoutineReaderTest : FunSpec({

    /** Eine Zeile aus `ALL_SOURCE`; der Zeilenumbruch gehoert zum Text. */
    fun sourceLine(type: String, name: String, line: Int, text: String) =
        mapOf("type" to type, "name" to name, "line" to line, "text" to "$text\n")

    /** `SQL_MACRO` und `POLYMORPHIC` tragen die Zeichenkette `'NULL'`, kein SQL-NULL. */
    fun propertyRow(
        name: String,
        deterministic: String = "NO",
        authid: String = "DEFINER",
        pipelined: String = "NO",
        aggregate: String = "NO",
        sqlMacro: String = "NULL",
        polymorphic: String = "NULL",
        parallel: String = "NO",
        resultCache: String = "NO",
    ) = mapOf(
        "object_name" to name, "deterministic" to deterministic, "authid" to authid,
        "pipelined" to pipelined, "aggregate" to aggregate, "sql_macro" to sqlMacro,
        "polymorphic" to polymorphic, "parallel" to parallel, "result_cache" to resultCache,
    )

    fun triggerRow(
        name: String,
        triggerType: String = "BEFORE EACH ROW",
        event: String = "INSERT",
        table: String = "ORDERS",
        baseObjectType: String = "TABLE",
        whenClause: String? = null,
        status: String = "ENABLED",
        actionType: String = "PL/SQL",
        crossEdition: String = "NO",
        referencing: String = "REFERENCING NEW AS NEW OLD AS OLD",
        tableOwner: String = "APP",
    ) = mapOf(
        "trigger_name" to name, "trigger_type" to triggerType, "triggering_event" to event,
        "table_name" to table, "table_owner" to tableOwner, "base_object_type" to baseObjectType,
        "when_clause" to whenClause, "status" to status, "action_type" to actionType,
        "crossedition" to crossEdition, "referencing_names" to referencing,
    )

    class Rig {
        val jdbc = mockk<JdbcOperations>()
        val notes = mutableListOf<SchemaReadNote>()
        val skipped = mutableListOf<SkippedObject>()

        init {
            listOf("all_source", "all_arguments", "all_procedures", "all_triggers",
                "all_trigger_cols", "all_trigger_ordering")
                .forEach { view ->
                    every { jdbc.queryList(match { it.contains("FROM $view") }, any()) } returns emptyList()
                }
            every { jdbc.queryList(match { it.contains("FROM all_dependencies") }, any()) } returns emptyList()
        }

        fun stub(view: String, rows: List<Map<String, Any?>>) {
            every { jdbc.queryList(match { it.contains("FROM $view") }, any()) } returns rows
        }

        fun read(options: SchemaReadOptions = SchemaReadOptions()) =
            OracleRoutineReader.read(jdbc, "APP", options, notes, skipped)
    }

    test("a function comes back with neutral parameter types, a body and a canonical key") {
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(
                sourceLine("FUNCTION", "P9_CALC", 1, "FUNCTION p9_calc(p_a IN NUMBER, p_b IN VARCHAR2)"),
                sourceLine("FUNCTION", "P9_CALC", 2, "RETURN NUMBER IS"),
                sourceLine("FUNCTION", "P9_CALC", 3, "BEGIN"),
                sourceLine("FUNCTION", "P9_CALC", 4, "  RETURN p_a;"),
                sourceLine("FUNCTION", "P9_CALC", 5, "END;"),
            ),
        )
        rig.stub(
            "all_arguments",
            listOf(
                // Position 0 ist der Rueckgabewert, nicht der erste Parameter.
                mapOf(
                    "object_name" to "P9_CALC", "position" to 0, "argument_name" to null,
                    "data_type" to "NUMBER", "type_name" to null, "in_out" to "OUT", "defaulted" to "N",
                ),
                mapOf(
                    "object_name" to "P9_CALC", "position" to 1, "argument_name" to "P_A",
                    "data_type" to "NUMBER", "type_name" to null, "in_out" to "IN", "defaulted" to "N",
                ),
                mapOf(
                    "object_name" to "P9_CALC", "position" to 2, "argument_name" to "P_B",
                    "data_type" to "VARCHAR2", "type_name" to null, "in_out" to "IN", "defaulted" to "N",
                ),
            ),
        )
        rig.stub("all_procedures", listOf(propertyRow("P9_CALC", authid = "CURRENT_USER", deterministic = "YES")))

        val result = rig.read()

        val fn = result.functions.getValue("P9_CALC(in:decimal,in:text)")
        fn.parameters.map { it.name to it.type } shouldBe listOf("P_A" to "decimal", "P_B" to "text")
        // Ohne Praezision: PL/SQL laesst einen beschraenkten Rueckgabetyp nicht zu.
        fn.returns!!.type shouldBe "decimal"
        fn.returns!!.precision.shouldBeNull()
        fn.body shouldBe "BEGIN\n  RETURN p_a;\nEND;"
        fn.sourceDialect shouldBe "oracle"
        fn.deterministic shouldBe true
        fn.security shouldBe RoutineSecurity.INVOKER
        rig.skipped.shouldBeEmpty()
    }

    test("a procedure keeps OUT and IN OUT directions") {
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(
                sourceLine("PROCEDURE", "P", 1, "PROCEDURE p(x OUT NUMBER, y IN OUT VARCHAR2) AS"),
                sourceLine("PROCEDURE", "P", 2, "BEGIN NULL; END;"),
            ),
        )
        rig.stub(
            "all_arguments",
            listOf(
                mapOf(
                    "object_name" to "P", "position" to 1, "argument_name" to "X",
                    "data_type" to "NUMBER", "type_name" to null, "in_out" to "OUT", "defaulted" to "N",
                ),
                mapOf(
                    "object_name" to "P", "position" to 2, "argument_name" to "Y",
                    "data_type" to "VARCHAR2", "type_name" to null, "in_out" to "IN/OUT", "defaulted" to "N",
                ),
            ),
        )
        rig.stub("all_procedures", listOf(propertyRow("P")))

        val proc = rig.read().procedures.getValue("P(out:decimal,inout:text)")
        proc.parameters.map { it.direction } shouldBe listOf(ParameterDirection.OUT, ParameterDirection.INOUT)
        proc.body shouldBe "BEGIN NULL; END;"
    }

    test("an ordinary routine is not mistaken for a SQL macro") {
        // `SQL_MACRO` und `POLYMORPHIC` tragen fuer jede gewoehnliche Routine
        // die Zeichenkette 'NULL'. Ein Test auf „nicht leer" uebersaehe das und
        // uebergaenge damit jede Routine.
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(sourceLine("PROCEDURE", "P", 1, "PROCEDURE p IS BEGIN NULL; END;")),
        )
        rig.stub("all_procedures", listOf(propertyRow("P")))

        rig.read().procedures.keys shouldBe setOf("P()")
        rig.skipped.shouldBeEmpty()
    }

    test("a SQL macro, a pipelined function and an aggregate are reported as R359") {
        listOf(
            propertyRow("F", sqlMacro = "SCALAR"),
            propertyRow("F", pipelined = "YES"),
            propertyRow("F", aggregate = "YES"),
            propertyRow("F", polymorphic = "TABLE"),
        ).forEach { properties ->
            val rig = Rig()
            rig.stub(
                "all_source",
                listOf(sourceLine("FUNCTION", "F", 1, "FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;")),
            )
            rig.stub("all_procedures", listOf(properties))
            rig.read().functions.shouldBeEmptyMap()
            rig.skipped.single().code shouldBe "R359"
        }
    }

    test("a defaulted parameter is reported as R360 instead of being silently dropped") {
        val rig = Rig()
        rig.stub("all_source", listOf(sourceLine("PROCEDURE", "P", 1, "PROCEDURE p(x IN NUMBER DEFAULT 1) IS BEGIN NULL; END;")))
        rig.stub(
            "all_arguments",
            listOf(
                mapOf(
                    "object_name" to "P", "position" to 1, "argument_name" to "X",
                    "data_type" to "NUMBER", "type_name" to null, "in_out" to "IN", "defaulted" to "Y",
                ),
            ),
        )
        rig.stub("all_procedures", listOf(propertyRow("P")))
        rig.read().procedures.shouldBeEmptyMap()
        rig.skipped.single().code shouldBe "R360"
    }

    test("a source without a top-level IS or AS is reported as R358") {
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(sourceLine("FUNCTION", "F", 1, "FUNCTION f(x IN NUMBER) RETURN NUMBER AGGREGATE USING t;")),
        )
        rig.stub("all_procedures", listOf(propertyRow("F")))
        rig.read().functions.shouldBeEmptyMap()
        rig.skipped.single().code shouldBe "R358"
    }

    test("PARALLEL_ENABLE and RESULT_CACHE are noted as R363 but do not stop the read") {
        val rig = Rig()
        rig.stub("all_source", listOf(sourceLine("FUNCTION", "F", 1, "FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;")))
        rig.stub("all_procedures", listOf(propertyRow("F", parallel = "YES", resultCache = "YES")))
        rig.read().functions.keys shouldBe setOf("F()")
        rig.skipped.shouldBeEmpty()
        rig.notes.single().code shouldBe "R363"
        rig.notes.single().message shouldContain "PARALLEL_ENABLE and RESULT_CACHE"
    }

    test("a row trigger comes back with events, timing, condition and a canonical key") {
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(
                sourceLine("TRIGGER", "TRG", 1, "TRIGGER trg"),
                sourceLine("TRIGGER", "TRG", 2, "BEFORE INSERT OR UPDATE ON orders"),
                sourceLine("TRIGGER", "TRG", 3, "FOR EACH ROW"),
                sourceLine("TRIGGER", "TRG", 4, "WHEN (NEW.amt > 10)"),
                sourceLine("TRIGGER", "TRG", 5, "BEGIN :NEW.amt := 1; END;"),
            ),
        )
        rig.stub("all_triggers", listOf(triggerRow("TRG", event = "INSERT OR UPDATE", whenClause = "NEW.amt > 10")))

        val trigger = rig.read().triggers.getValue("ORDERS::TRG")
        trigger.events shouldBe setOf(TriggerEvent.INSERT, TriggerEvent.UPDATE)
        trigger.timing shouldBe TriggerTiming.BEFORE
        trigger.forEach shouldBe TriggerForEach.ROW
        trigger.condition shouldBe "NEW.amt > 10"
        trigger.body shouldBe "BEGIN :NEW.amt := 1; END;"
    }

    test("an INSTEAD OF trigger on a view is read, not excluded") {
        // `base_object_type = 'TABLE'` als Filter haette sie stumm verloren.
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(
                sourceLine("TRIGGER", "TIO", 1, "TRIGGER tio"),
                sourceLine("TRIGGER", "TIO", 2, "INSTEAD OF INSERT ON v"),
                sourceLine("TRIGGER", "TIO", 3, "BEGIN NULL; END;"),
            ),
        )
        rig.stub(
            "all_triggers",
            listOf(triggerRow("TIO", triggerType = "INSTEAD OF", table = "V", baseObjectType = "VIEW")),
        )
        val trigger = rig.read().triggers.getValue("V::TIO")
        trigger.timing shouldBe TriggerTiming.INSTEAD_OF
        // Oracle feuert INSTEAD OF immer zeilenweise, auch ohne die Klausel.
        trigger.forEach shouldBe TriggerForEach.ROW
    }

    test("a statement trigger keeps its granularity") {
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(sourceLine("TRIGGER", "TS", 1, "TRIGGER ts AFTER DELETE ON orders BEGIN NULL; END;")),
        )
        rig.stub("all_triggers", listOf(triggerRow("TS", triggerType = "AFTER STATEMENT", event = "DELETE")))
        rig.read().triggers.getValue("ORDERS::TS").forEach shouldBe TriggerForEach.STATEMENT
    }

    test("dropped UPDATE OF columns are noted as R362 while the trigger is still read") {
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(sourceLine("TRIGGER", "TRG", 1, "TRIGGER trg BEFORE UPDATE OF amt ON orders BEGIN NULL; END;")),
        )
        rig.stub("all_triggers", listOf(triggerRow("TRG", event = "UPDATE")))
        // Die Spaltenliste steht in ALL_TRIGGER_COLS mit COLUMN_LIST = 'YES',
        // nicht in ALL_TRIGGERS.COLUMN_NAME.
        rig.stub("all_trigger_cols", listOf(mapOf("trigger_name" to "TRG", "column_name" to "AMT")))

        rig.read().triggers.keys shouldBe setOf("ORDERS::TRG")
        rig.notes.single().code shouldBe "R362"
        rig.notes.single().message shouldContain "fires on every update"
    }

    test("triggers the neutral model cannot express are reported as R361") {
        val cases = listOf(
            triggerRow("T", triggerType = "COMPOUND"),
            triggerRow("T", event = "CREATE"),
            triggerRow("T", actionType = "CALL"),
            triggerRow("T", status = "DISABLED"),
            triggerRow("T", crossEdition = "YES"),
            triggerRow("T", referencing = "REFERENCING NEW AS N OLD AS O"),
        )
        cases.forEach { row ->
            val rig = Rig()
            rig.stub(
                "all_source",
                listOf(sourceLine("TRIGGER", "T", 1, "TRIGGER t BEFORE INSERT ON orders BEGIN NULL; END;")),
            )
            rig.stub("all_triggers", listOf(row))
            rig.read().triggers.shouldBeEmptyMap()
            rig.skipped.single().code shouldBe "R361"
        }
    }

    test("read options switch each object kind off separately") {
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(
                sourceLine("FUNCTION", "F", 1, "FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;"),
                sourceLine("PROCEDURE", "P", 1, "PROCEDURE p IS BEGIN NULL; END;"),
                sourceLine("TRIGGER", "T", 1, "TRIGGER t BEFORE INSERT ON orders BEGIN NULL; END;"),
            ),
        )
        rig.stub("all_procedures", listOf(propertyRow("F"), propertyRow("P")))
        rig.stub("all_triggers", listOf(triggerRow("T")))

        val result = rig.read(
            SchemaReadOptions(includeFunctions = true, includeProcedures = false, includeTriggers = false),
        )
        result.functions.keys shouldBe setOf("F()")
        result.procedures.shouldBeEmptyMap()
        result.triggers.shouldBeEmptyMap()
    }

    test("dependencies come from ALL_DEPENDENCIES and are keyed by type and name") {
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(sourceLine("FUNCTION", "F", 1, "FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;")),
        )
        rig.stub("all_procedures", listOf(propertyRow("F")))
        rig.stub(
            "all_dependencies",
            listOf(
                mapOf(
                    "name" to "F", "type" to "FUNCTION", "referenced_owner" to "APP",
                    "referenced_name" to "ORDERS", "referenced_type" to "TABLE",
                ),
            ),
        )
        rig.read().functions.getValue("F()").dependencies!!.tables shouldBe listOf("ORDERS")
    }

    test("a routine with no dependency row keeps a null projection instead of claiming none") {
        val rig = Rig()
        rig.stub(
            "all_source",
            listOf(sourceLine("FUNCTION", "F", 1, "FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;")),
        )
        rig.stub("all_procedures", listOf(propertyRow("F")))
        rig.read().functions.getValue("F()").dependencies.shouldBeNull()
    }

    test("a user-defined parameter type comes from TYPE_NAME, not from the category") {
        // ALL_ARGUMENTS traegt bei einem UDT nur `OBJECT`/`VARRAY`/`TABLE` in
        // DATA_TYPE; der Name steht in TYPE_NAME. Die Kategorie zu nehmen
        // ergaebe `IN OBJECT` -- kein gueltiges PL/SQL.
        val rig = Rig()
        rig.stub("all_source", listOf(sourceLine("PROCEDURE", "P", 1, "PROCEDURE p(x IN t) IS BEGIN NULL; END;")))
        rig.stub(
            "all_arguments",
            listOf(
                mapOf(
                    "object_name" to "P", "position" to 1, "argument_name" to "X",
                    "data_type" to "OBJECT", "type_name" to "P9_OBJ", "in_out" to "IN", "defaulted" to "N",
                ),
            ),
        )
        rig.stub("all_procedures", listOf(propertyRow("P")))
        rig.read().procedures.keys shouldBe setOf("P(in:p9_obj)")
    }

    test("a category without a usable type name is reported as R360") {
        // `REF CURSOR` hat keinen Namen in TYPE_NAME; `IN REF CURSOR` waere
        // kein gueltiges PL/SQL.
        val rig = Rig()
        rig.stub("all_source", listOf(sourceLine("PROCEDURE", "P", 1, "PROCEDURE p(c OUT SYS_REFCURSOR) IS BEGIN NULL; END;")))
        rig.stub(
            "all_arguments",
            listOf(
                mapOf(
                    "object_name" to "P", "position" to 1, "argument_name" to "C",
                    "data_type" to "REF CURSOR", "type_name" to null, "in_out" to "OUT", "defaulted" to "N",
                ),
            ),
        )
        rig.stub("all_procedures", listOf(propertyRow("P")))
        rig.read().procedures.shouldBeEmptyMap()
        rig.skipped.single().code shouldBe "R360"
    }

    test("DETERMINISTIC NO becomes null so the migration converges") {
        // `NO` ist Oracles Voreinstellung. Als `false` abgelegt plante jeder
        // Lauf erneut ein ReplaceFunction gegen eine Schemadatei, die die
        // Angabe gar nicht fuehrt.
        val rig = Rig()
        rig.stub("all_source", listOf(sourceLine("FUNCTION", "F", 1, "FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;")))
        rig.stub("all_procedures", listOf(propertyRow("F", deterministic = "NO")))
        rig.read().functions.getValue("F()").deterministic.shouldBeNull()
    }

    test("a FOLLOWS ordering is reported as R361 instead of being dropped") {
        val rig = Rig()
        rig.stub("all_source", listOf(sourceLine("TRIGGER", "T", 1, "TRIGGER t BEFORE INSERT ON orders FOLLOWS a BEGIN NULL; END;")))
        rig.stub("all_triggers", listOf(triggerRow("T")))
        rig.stub("all_trigger_ordering", listOf(mapOf("trigger_name" to "T")))
        rig.read().triggers.shouldBeEmptyMap()
        rig.skipped.single().code shouldBe "R361"
    }

    test("a trigger on a table in another schema is reported instead of losing its qualifier") {
        // Das Modell traegt nur den blanken Tabellennamen; die Struktur-
        // Validierung des Generate-Pfads meldete ihn sonst als E018.
        val rig = Rig()
        rig.stub("all_source", listOf(sourceLine("TRIGGER", "T", 1, "TRIGGER t BEFORE INSERT ON other.t BEGIN NULL; END;")))
        rig.stub("all_triggers", listOf(triggerRow("T", table = "T", tableOwner = "OTHER")))
        rig.read().triggers.shouldBeEmptyMap()
        rig.skipped.single().code shouldBe "R361"
    }
})
