package dev.dmigrate.driver.mssql

import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ParameterDirection
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.inspectors.forAll
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class MssqlRoutineDdlTest : FunSpec({

    val quote: (String) -> String = { "[$it]" }

    fun trigger(
        timing: TriggerTiming = TriggerTiming.AFTER,
        forEach: TriggerForEach = TriggerForEach.STATEMENT,
        condition: String? = null,
        events: Set<TriggerEvent> = setOf(TriggerEvent.INSERT),
    ) = TriggerDefinition(
        table = "orders", events = events, timing = timing, forEach = forEach,
        condition = condition, body = "BEGIN END", sourceDialect = "mssql",
    )

    test("neutral parameter types map to T-SQL, OUT parameters carry OUTPUT") {
        val proc = ProcedureDefinition(
            parameters = listOf(
                ParameterDefinition("a", "integer"),
                ParameterDefinition("b", "text"),
                ParameterDefinition("c", "uuid"),
                ParameterDefinition("d", "datetime"),
                ParameterDefinition("e", "boolean", ParameterDirection.INOUT),
                ParameterDefinition("f", "binary", ParameterDirection.OUT),
            ),
            body = "BEGIN END",
        )
        MssqlRoutineDdl.procedureSql("p", proc, "BEGIN END", quote) shouldContain
            "(@a INT, @b NVARCHAR(MAX), @c UNIQUEIDENTIFIER, @d DATETIME2, @e BIT OUTPUT, @f VARBINARY(MAX) OUTPUT)"
    }

    // Der Reverse legt Parameternamen ohne `@` ab; handgeschriebene Schemata
    // duerfen es mitbringen. Beide ergeben denselben Parameter.
    test("a parameter name keeps exactly one @ prefix") {
        val proc = ProcedureDefinition(
            parameters = listOf(ParameterDefinition("@id", "integer"), ParameterDefinition("id2", "integer")),
        )
        MssqlRoutineDdl.procedureSql("p", proc, "BEGIN END", quote) shouldContain "(@id INT, @id2 INT)"
    }

    // `CREATE PROCEDURE p () AS` scheitert an „Incorrect syntax near ')'";
    // Funktionen verlangen die Klammern dagegen immer.
    test("a parameterless procedure renders without parentheses, a function with them") {
        MssqlRoutineDdl.procedureSql("p", ProcedureDefinition(), "BEGIN END", quote) shouldContain
            "CREATE OR ALTER PROCEDURE [p] AS"
        MssqlRoutineDdl.functionSql("f", FunctionDefinition(returns = ReturnType("integer")), "BEGIN END", quote)
            .shouldContain("CREATE OR ALTER FUNCTION [f]()")
    }

    test("a decimal return type keeps precision and scale") {
        val fn = FunctionDefinition(returns = ReturnType("decimal", precision = 12, scale = 4))
        MssqlRoutineDdl.functionSql("f", fn, "BEGIN RETURN 0 END", quote) shouldContain "RETURNS DECIMAL(12,4) AS"
    }

    test("a function without a return type is unrenderable") {
        MssqlRoutineDdl.unsupportedFunctionShape("f", FunctionDefinition(body = "x"))
            .shouldNotBeNull().reason shouldContain "RETURNS clause"
        MssqlRoutineDdl.unsupportedFunctionShape("f", FunctionDefinition(returns = ReturnType("integer")))
            .shouldBeNull()
    }

    test("multi-event triggers render comma-separated in canonical order") {
        val sql = MssqlRoutineDdl.triggerSql(
            "trg",
            trigger(events = setOf(TriggerEvent.DELETE, TriggerEvent.INSERT)),
            "BEGIN END",
            quote,
        )
        sql shouldContain "CREATE OR ALTER TRIGGER [trg] ON [orders]\nAFTER INSERT, DELETE AS"
    }

    test("INSTEAD OF renders with a space") {
        MssqlRoutineDdl.triggerSql("trg", trigger(timing = TriggerTiming.INSTEAD_OF), "BEGIN END", quote)
            .shouldContain("INSTEAD OF INSERT AS")
    }

    test("trigger shapes T-SQL does not have are reported, not reinterpreted") {
        listOf(
            trigger(timing = TriggerTiming.BEFORE) to "AFTER and INSTEAD OF",
            trigger(forEach = TriggerForEach.ROW) to "once per statement",
            trigger(condition = "id > 0") to "WHEN condition",
        ).forAll { (def, expected) ->
            MssqlRoutineDdl.unsupportedTriggerShape("trg", def).shouldNotBeNull().reason shouldContain expected
        }
        MssqlRoutineDdl.unsupportedTriggerShape("trg", trigger()).shouldBeNull()
    }

    test("invert covers all four CREATE OR ALTER forms and nothing else") {
        val nameAfter: (String, String) -> String = { sql, keyword ->
            sql.substring(keyword.length).trimStart().substringBefore('(').substringBefore(' ').trim()
        }
        MssqlRoutineDdl.invert("CREATE OR ALTER FUNCTION [f](@a INT)", nameAfter) shouldBe
            "DROP FUNCTION IF EXISTS [f];"
        MssqlRoutineDdl.invert("CREATE OR ALTER PROCEDURE [p]()", nameAfter) shouldBe
            "DROP PROCEDURE IF EXISTS [p];"
        MssqlRoutineDdl.invert("CREATE OR ALTER TRIGGER [t] ON [x]", nameAfter) shouldBe
            "DROP TRIGGER IF EXISTS [t];"
        MssqlRoutineDdl.invert("CREATE OR ALTER VIEW [v] AS", nameAfter) shouldBe "DROP VIEW IF EXISTS [v];"
        MssqlRoutineDdl.invert("CREATE TABLE [t] (id INT)", nameAfter).shouldBeNull()
    }

    // Der Fallback reicht unbekannte Namen durch — richtig fuer einen
    // benutzerdefinierten T-SQL-Typ, falsch fuer einen neutralen Namen ohne
    // Abbildung: `@x ARRAY` lehnt der Server mit „Cannot find data type" ab.
    test("neutral type names without a T-SQL counterpart are reported, native names pass through") {
        val withArray = ProcedureDefinition(parameters = listOf(ParameterDefinition("x", "array")))
        MssqlRoutineDdl.unsupportedProcedureShape("p", withArray).shouldNotBeNull().reason shouldContain "array"

        val withUdt = ProcedureDefinition(parameters = listOf(ParameterDefinition("x", "my_udt")))
        MssqlRoutineDdl.unsupportedProcedureShape("p", withUdt).shouldBeNull()
        MssqlRoutineDdl.procedureSql("p", withUdt, "BEGIN END", quote) shouldContain "@x MY_UDT"
    }

    test("identifier renders as INT, a precisionless decimal spells out the T-SQL default") {
        val proc = ProcedureDefinition(
            parameters = listOf(ParameterDefinition("id", "identifier"), ParameterDefinition("amount", "decimal")),
        )
        MssqlRoutineDdl.procedureSql("p", proc, "BEGIN END", quote) shouldContain "(@id INT, @amount DECIMAL(18,0))"
    }
})
