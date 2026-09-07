package dev.dmigrate.driver.oracle

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.ColumnDefinition
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TableDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/** Routinen und Trigger im Diff-Pfad, beide Richtungen. */
class OracleDiffRoutineOpsTest : FunSpec({

    val gen = OracleDiffDdlGenerator()

    fun schema(
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
        tables: Map<String, TableDefinition> = mapOf(
            "orders" to TableDefinition(columns = mapOf("a" to ColumnDefinition(NeutralType.Integer, ordinal = 1))),
        ),
    ) = SchemaDefinition(
        name = "App", version = "1",
        tables = tables, functions = functions, procedures = procedures, triggers = triggers,
    )

    fun plan(op: DiffOperation, current: SchemaDefinition = schema(), desired: SchemaDefinition = schema()) =
        DiffResult(
            current = DiffEndpoint(schemaName = "App"),
            desired = DiffEndpoint(schemaName = "App"),
            schemaDiff = SchemaDiff(),
            operations = listOf(op),
            currentSchema = current,
            desiredSchema = desired,
        )

    val fn = FunctionDefinition(returns = ReturnType("integer"), body = "BEGIN RETURN 1; END;")
    val proc = ProcedureDefinition(body = "BEGIN NULL; END;")
    val trigger = TriggerDefinition(
        table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.AFTER, body = "BEGIN NULL; END;",
    )

    fun createFunction() = DiffOperation.CreateFunction(
        id = "cf", objectRef = DiffObjectRef(DiffObjectType.FUNCTION, listOf("calc()")), function = fn,
    )

    test("CreateFunction renders CREATE OR REPLACE up and DROP down") {
        val up = gen.generateUp(plan(createFunction(), desired = schema(functions = mapOf("calc()" to fn))), DdlGenerationOptions())
        up.statements.single().sql shouldBe
            "CREATE OR REPLACE FUNCTION \"calc\"\nRETURN NUMBER IS\nBEGIN RETURN 1; END;"
        // Der Bezeichner kommt aus dem kanonischen Key, nicht der Key selbst:
        // `"calc()"` waere ein unaufrufbarer Name.
        up.statements.single().sql shouldNotContain "calc()"

        val down = gen.generateDown(plan(createFunction(), current = schema(functions = mapOf("calc()" to fn))), DdlGenerationOptions())
        down.statements.single().sql shouldBe "DROP FUNCTION \"calc\";"
    }

    test("ReplaceProcedure renders the after body up and the before body down") {
        val before = proc.copy(body = "BEGIN NULL; END;")
        val after = proc.copy(body = "BEGIN COMMIT; END;")
        val op = DiffOperation.ReplaceProcedure(
            id = "rp", objectRef = DiffObjectRef(DiffObjectType.PROCEDURE, listOf("touch()")),
            before = before, after = after,
        )
        gen.generateUp(plan(op, desired = schema(procedures = mapOf("touch()" to after))), DdlGenerationOptions())
            .statements.single().sql shouldContain "BEGIN COMMIT; END;"
        gen.generateDown(plan(op, current = schema(procedures = mapOf("touch()" to before))), DdlGenerationOptions())
            .statements.single().sql shouldContain "BEGIN NULL; END;"
    }

    test("CreateTrigger uses the table from the key and renders no trailing semicolon") {
        val op = DiffOperation.CreateTrigger(
            id = "ct", objectRef = DiffObjectRef(DiffObjectType.TRIGGER, listOf("orders::touch")), trigger = trigger,
        )
        val sql = gen.generateUp(plan(op, desired = schema(triggers = mapOf("orders::touch" to trigger))), DdlGenerationOptions())
            .statements.single().sql
        sql shouldContain "CREATE OR REPLACE TRIGGER \"touch\""
        sql.endsWith("END;") shouldBe true
    }

    test("renaming a trigger is native; renaming a function or procedure blocks") {
        val renameTrigger = DiffOperation.RenameTrigger(
            id = "rt", objectRef = DiffObjectRef(DiffObjectType.TRIGGER, listOf("orders::touch")),
            tableName = "orders", fromName = "touch", toName = "poke",
            bodyHash = null, overlaySource = "test", overlayEntryId = "e1", overlayHash = null,
        )
        gen.generateUp(plan(renameTrigger), DdlGenerationOptions()).statements.single().sql shouldBe
            "ALTER TRIGGER \"touch\" RENAME TO \"poke\";"

        val renameFunction = DiffOperation.RenameFunction(
            id = "rf", objectRef = DiffObjectRef(DiffObjectType.FUNCTION, listOf("calc()")),
            fromName = "calc", toName = "compute", signature = emptyList(),
            bodyHash = null, overlaySource = "test", overlayEntryId = "e1", overlayHash = null,
        )
        val blocked = gen.generateUp(plan(renameFunction), DdlGenerationOptions())
        blocked.isBlocked shouldBe true
        blocked.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        blocked.statements.shouldBeEmpty()
    }

    test("the down direction of a rename swaps the trigger names") {
        val op = DiffOperation.RenameTrigger(
            id = "rt", objectRef = DiffObjectRef(DiffObjectType.TRIGGER, listOf("orders::touch")),
            tableName = "orders", fromName = "touch", toName = "poke",
            bodyHash = null, overlaySource = "test", overlayEntryId = "e1", overlayHash = null,
        )
        gen.generateDown(plan(op), DdlGenerationOptions()).statements.single().sql shouldBe
            "ALTER TRIGGER \"poke\" RENAME TO \"touch\";"
    }

    test("a body from another dialect blocks with the same judgement as the generate path") {
        val foreign = fn.copy(sourceDialect = "postgresql")
        val op = DiffOperation.CreateFunction(
            id = "cf", objectRef = DiffObjectRef(DiffObjectType.FUNCTION, listOf("calc()")), function = foreign,
        )
        val result = gen.generateUp(plan(op, desired = schema(functions = mapOf("calc()" to foreign))), DdlGenerationOptions())
        result.isBlocked shouldBe true
        result.diagnostics.single { it.operationId == "cf" }.code shouldBe "E053"
    }

    test("a missing body blocks the down direction as ROLLBACK_NOT_POSSIBLE") {
        val bodiless = fn.copy(body = null)
        val op = DiffOperation.DropFunction(
            id = "df", objectRef = DiffObjectRef(DiffObjectType.FUNCTION, listOf("calc()")), function = bodiless,
        )
        // Rueckbau eines Drops heisst: wieder anlegen -- ohne Rumpf unmoeglich.
        val down = gen.generateDown(plan(op, current = schema(functions = mapOf("calc()" to bodiless))), DdlGenerationOptions())
        down.isBlocked shouldBe true
        down.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("two overloads in the target schema block instead of silently replacing each other") {
        val target = schema(
            functions = mapOf(
                "calc(in:integer)" to fn,
                "calc(in:text)" to fn,
            ),
        )
        val op = DiffOperation.CreateFunction(
            id = "cf", objectRef = DiffObjectRef(DiffObjectType.FUNCTION, listOf("calc(in:integer)")), function = fn,
        )
        val result = gen.generateUp(plan(op, desired = target), DdlGenerationOptions())
        result.isBlocked shouldBe true
        result.diagnostics.single { it.operationId == "cf" }.message shouldContain "no overloading"
    }

    test("an INSTEAD OF trigger on a table blocks, judged against the target schema") {
        val bad = TriggerDefinition(
            table = "orders", event = TriggerEvent.INSERT, timing = TriggerTiming.INSTEAD_OF,
            body = "BEGIN NULL; END;",
        )
        val op = DiffOperation.CreateTrigger(
            id = "ct", objectRef = DiffObjectRef(DiffObjectType.TRIGGER, listOf("orders::touch")), trigger = bad,
        )
        val result = gen.generateUp(plan(op, desired = schema(triggers = mapOf("orders::touch" to bad))), DdlGenerationOptions())
        result.isBlocked shouldBe true
        result.diagnostics.single { it.operationId == "ct" }.message shouldContain "only on views"
    }
})
