package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.RoutineSecurity
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * Die PL/SQL-Huellen. Die Zusicherungen hier bilden Messungen gegen einen
 * echten Server ab: jede Abweichung erzeugte dort eine `INVALID`-Routine oder
 * einen Syntaxfehler.
 */
class OracleRoutineDdlTest : FunSpec({

    val quote = { name: String -> "\"$name\"" }

    test("a function renders parameters and return type without length or precision") {
        val fn = FunctionDefinition(
            parameters = listOf(
                ParameterDefinition("a", "decimal"),
                ParameterDefinition("b", "text"),
            ),
            returns = ReturnType("decimal", precision = 10, scale = 2),
            body = "BEGIN RETURN a; END;",
        )
        // `IN VARCHAR2(10)` und `RETURN NUMBER(10,2)` erzeugen die Routine INVALID.
        OracleRoutineDdl.functionSql("calc", fn, "BEGIN RETURN a; END;", quote) shouldBe
            "CREATE OR REPLACE FUNCTION \"calc\"(\"a\" IN NUMBER, \"b\" IN CLOB)\n" +
            "RETURN NUMBER IS\nBEGIN RETURN a; END;"
    }

    test("unbegrenzter Text wird CLOB, nicht VARCHAR2 — wie in einer Spalte auch") {
        // Ein PL/SQL-VARCHAR2 endet bei 32767 Zeichen; live gemessen bricht der
        // Aufruf dort mit ORA-06502 ab, waehrend derselbe Wert an einem
        // CLOB-Parameter durchgeht. Der Spalten-Pfad rendert `Text()` ebenfalls
        // als CLOB, PostgreSQL `text` und SQL Server `NVARCHAR(MAX)`.
        OracleRoutineDdl.paramTypeSql("text") shouldBe "CLOB"
    }

    test("begrenzte Textarten bleiben VARCHAR2 — dort ist es keine Verengung") {
        OracleRoutineDdl.paramTypeSql("uuid") shouldBe "VARCHAR2"
        OracleRoutineDdl.paramTypeSql("email") shouldBe "VARCHAR2"
        OracleRoutineDdl.paramTypeSql("enum") shouldBe "VARCHAR2"
    }

    test("char bleibt CHAR — sonst faellt die Auffuellung auf feste Laenge weg") {
        OracleRoutineDdl.paramTypeSql("char") shouldBe "CHAR"
    }

    test("an empty parameter list renders without parentheses") {
        // `PROCEDURE p()` und `FUNCTION f()` sind Uebersetzungsfehler.
        OracleRoutineDdl.procedureSql("p", ProcedureDefinition(), "BEGIN NULL; END;", quote) shouldBe
            "CREATE OR REPLACE PROCEDURE \"p\" IS\nBEGIN NULL; END;"
        val fn = FunctionDefinition(returns = ReturnType("integer"))
        OracleRoutineDdl.functionSql("f", fn, "BEGIN RETURN 1; END;", quote) shouldNotContain "\"f\"()"
    }

    test("parameter directions render as IN, OUT and IN OUT") {
        val proc = ProcedureDefinition(
            parameters = listOf(
                ParameterDefinition("i", "integer", ParameterDirection.IN),
                ParameterDefinition("o", "integer", ParameterDirection.OUT),
                ParameterDefinition("b", "text", ParameterDirection.INOUT),
            ),
        )
        OracleRoutineDdl.procedureSql("p", proc, "BEGIN NULL; END;", quote) shouldContain
            "(\"i\" IN NUMBER, \"o\" OUT NUMBER, \"b\" IN OUT CLOB)"
    }

    test("DETERMINISTIC and AUTHID CURRENT_USER render; DEFINER stays implicit") {
        val invoker = FunctionDefinition(
            returns = ReturnType("integer"),
            deterministic = true,
            security = RoutineSecurity.INVOKER,
        )
        OracleRoutineDdl.functionSql("f", invoker, "BEGIN RETURN 1; END;", quote) shouldContain
            "RETURN NUMBER DETERMINISTIC AUTHID CURRENT_USER IS"
        // DEFINER ist Oracles Voreinstellung; sie auszuschreiben behauptete
        // einen Unterschied, wo keiner ist.
        val definer = invoker.copy(deterministic = false, security = RoutineSecurity.DEFINER)
        OracleRoutineDdl.functionSql("f", definer, "BEGIN RETURN 1; END;", quote) shouldNotContain "AUTHID"
    }

    test("a row trigger renders FOR EACH ROW and parenthesises the WHEN condition") {
        val trigger = TriggerDefinition(
            table = "orders",
            events = setOf(TriggerEvent.INSERT, TriggerEvent.UPDATE),
            timing = TriggerTiming.BEFORE,
            forEach = TriggerForEach.ROW,
            // Der Katalog liefert die Bedingung ohne Klammern.
            condition = "NEW.amt > 10",
        )
        OracleRoutineDdl.triggerSql("trg", trigger, "BEGIN NULL; END;", quote) shouldBe
            "CREATE OR REPLACE TRIGGER \"trg\"\nBEFORE INSERT OR UPDATE ON \"orders\"\n" +
            "FOR EACH ROW\nWHEN (NEW.amt > 10)\nBEGIN NULL; END;"
    }

    test("a statement trigger omits FOR EACH ROW") {
        val trigger = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.DELETE,
            timing = TriggerTiming.AFTER,
            forEach = TriggerForEach.STATEMENT,
        )
        OracleRoutineDdl.triggerSql("trg", trigger, "BEGIN NULL; END;", quote) shouldNotContain "FOR EACH ROW"
    }

    test("an INSTEAD OF trigger omits FOR EACH ROW because Oracle always fires it per row") {
        val trigger = TriggerDefinition(
            table = "v",
            event = TriggerEvent.INSERT,
            timing = TriggerTiming.INSTEAD_OF,
            forEach = TriggerForEach.ROW,
        )
        val sql = OracleRoutineDdl.triggerSql("trg", trigger, "BEGIN NULL; END;", quote)
        sql shouldContain "INSTEAD OF INSERT ON \"v\""
        sql shouldNotContain "FOR EACH ROW"
    }

    test("no rendered routine ends with an extra semicolon or a slash") {
        val fn = FunctionDefinition(returns = ReturnType("integer"))
        val statements = listOf(
            OracleRoutineDdl.functionSql("f", fn, "BEGIN RETURN 1; END;", quote),
            OracleRoutineDdl.procedureSql("p", ProcedureDefinition(), "BEGIN NULL; END;", quote),
            OracleRoutineDdl.triggerSql(
                "t",
                TriggerDefinition(table = "x", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER),
                "BEGIN NULL; END;",
                quote,
            ),
        )
        // Beides laesst execute() gelingen und die Routine INVALID zurueck.
        statements.forEach {
            it.endsWith("END;") shouldBe true
            it shouldNotContain "\n/"
        }
    }

    test("an unmapped neutral name falls through as a native Oracle type") {
        // Ein benutzerdefinierter Typ, den der Reverse namentlich gelesen hat.
        OracleRoutineDdl.paramTypeSql("my_obj_type") shouldBe "MY_OBJ_TYPE"
    }

    test("a WHEN condition with inner parentheses is not stripped") {
        // Der Katalog liefert `(a) AND (b)` -- das faengt mit `(` an und hoert
        // mit `)` auf, ohne von einem Klammerpaar umschlossen zu sein. Ein
        // blindes Abziehen der Randzeichen ergaebe `WHEN (a) AND (b)`.
        val trigger = TriggerDefinition(
            table = "orders",
            event = TriggerEvent.UPDATE,
            timing = TriggerTiming.BEFORE,
            condition = "(NEW.amt > 10) AND (NEW.note IS NOT NULL)",
        )
        OracleRoutineDdl.triggerSql("trg", trigger, "BEGIN NULL; END;", quote) shouldContain
            "WHEN ((NEW.amt > 10) AND (NEW.note IS NOT NULL))"
    }

    test("a boolean parameter stays BOOLEAN instead of becoming NUMBER") {
        // Anders als eine Spalte darf ein PL/SQL-Parameter BOOLEAN sein;
        // NUMBER daraus zu machen aenderte den Aufrufvertrag.
        val proc = ProcedureDefinition(parameters = listOf(ParameterDefinition("flag", "boolean")))
        OracleRoutineDdl.procedureSql("p", proc, "BEGIN NULL; END;", quote) shouldContain
            "(\"flag\" IN BOOLEAN)"
    }
})
