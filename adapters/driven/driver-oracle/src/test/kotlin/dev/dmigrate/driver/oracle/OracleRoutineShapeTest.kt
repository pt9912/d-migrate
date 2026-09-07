package dev.dmigrate.driver.oracle

import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Das Urteil, ob sich eine Routine oder ein Trigger in Oracle darstellen laesst. */
class OracleRoutineShapeTest : FunSpec({

    val tables = mapOf("orders" to TableDefinition(columns = mapOf("a" to ColumnDefinition(NeutralType.Integer, ordinal = 1))))

    test("a function without a return type cannot be rendered") {
        OracleRoutineShape.unsupportedFunctionShape("f", FunctionDefinition(body = "BEGIN NULL; END;"))!!
            .reason shouldContain "RETURN clause"
    }

    test("a function with a return type and mappable parameters is fine") {
        val fn = FunctionDefinition(
            parameters = listOf(ParameterDefinition("a", "text")),
            returns = ReturnType("integer"),
        )
        OracleRoutineShape.unsupportedFunctionShape("f", fn).shouldBeNull()
    }

    test("neutral names Oracle has no unconstrained parameter type for are rejected") {
        // `time` gibt es nur als VARCHAR2(n) an einer Spalte -- eine Signatur
        // darf keine Laenge tragen; `array`, `geometry` und `fulltext` haben
        // keine Signaturform.
        listOf("time", "array", "geometry", "fulltext").forEach { type ->
            val proc = ProcedureDefinition(parameters = listOf(ParameterDefinition("p", type)))
            OracleRoutineShape.unsupportedProcedureShape("p", proc)!!.reason shouldContain type
        }
    }

    test("a WHEN condition on a statement trigger is rejected (ORA-04077)") {
        val trigger = TriggerDefinition(
            table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER,
            forEach = TriggerForEach.STATEMENT, condition = "NEW.a > 1",
        )
        OracleRoutineShape.unsupportedTriggerShape("t", trigger, tables)!!.reason shouldContain "ORA-04077"
    }

    test("a WHEN condition on an INSTEAD OF trigger is rejected (ORA-25004)") {
        val trigger = TriggerDefinition(
            table = "v", event = TriggerEvent.INSERT, timing = TriggerTiming.INSTEAD_OF,
            forEach = TriggerForEach.ROW, condition = "NEW.a > 1",
        )
        OracleRoutineShape.unsupportedTriggerShape("t", trigger, emptyMap())!!.reason shouldContain "ORA-25004"
    }

    test("BEFORE on a view and INSTEAD OF on a table are both rejected") {
        val beforeOnView = TriggerDefinition(
            table = "v", event = TriggerEvent.INSERT, timing = TriggerTiming.BEFORE,
        )
        OracleRoutineShape.unsupportedTriggerShape("t", beforeOnView, tables)!!.reason shouldContain "ORA-25001"

        val insteadOfOnTable = TriggerDefinition(
            table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.INSTEAD_OF,
        )
        OracleRoutineShape.unsupportedTriggerShape("t", insteadOfOnTable, tables)!!.reason shouldContain "only on views"
    }

    test("without a table map the target is not judged") {
        val trigger = TriggerDefinition(table = "v", event = TriggerEvent.INSERT, timing = TriggerTiming.BEFORE)
        OracleRoutineShape.unsupportedTriggerShape("t", trigger, null).shouldBeNull()
    }

    test("a body from another dialect is not translated; a PL/SQL body passes") {
        OracleRoutineShape.bodyProblem("function", "f", "BEGIN NULL; END;", "postgresql")!!
            .reason shouldContain "postgresql"
        OracleRoutineShape.bodyProblem("function", "f", "BEGIN NULL; END;", "oracle").shouldBeNull()
        OracleRoutineShape.bodyProblem("function", "f", null, "oracle")!!.reason shouldContain "no body"
    }

    test("two overloads fall on the same Oracle name and are reported") {
        val keys = setOf("calc(in:integer)", "calc(in:text)", "other()")
        val colliding = OracleRoutineShape.collidingNames(keys) {
            dev.dmigrate.core.identity.ObjectKeyCodec.routineName(it)
        }
        colliding.keys shouldBe setOf("calc")
        OracleRoutineShape.nameCollision("function", "calc", colliding)!!.reason shouldContain "no overloading"
        OracleRoutineShape.nameCollision("function", "other", colliding).shouldBeNull()
    }

    test("two same-named triggers on different tables collide schema-wide") {
        val keys = setOf("orders::touch", "items::touch")
        val colliding = OracleRoutineShape.collidingNames(keys) {
            dev.dmigrate.core.identity.ObjectKeyCodec.triggerName(it)
        }
        OracleRoutineShape.nameCollision("trigger", "touch", colliding)!!.reason shouldContain "ORA-04095"
    }

    test("renaming a standalone routine is impossible") {
        OracleRoutineShape.renameUnsupported("function", "old", "new").reason shouldContain "cannot rename"
    }
})
