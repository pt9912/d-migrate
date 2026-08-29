package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.FunctionDiff
import dev.dmigrate.core.diff.NamedFunction
import dev.dmigrate.core.diff.NamedProcedure
import dev.dmigrate.core.diff.NamedTrigger
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.model.FunctionDefinition
import dev.dmigrate.core.model.ParameterDefinition
import dev.dmigrate.core.model.ProcedureDefinition
import dev.dmigrate.core.model.ReturnType
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.TriggerDefinition
import dev.dmigrate.core.model.TriggerEvent
import dev.dmigrate.core.model.TriggerForEach
import dev.dmigrate.core.model.TriggerTiming
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain as listShouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Sub-Slice 9c: Routinen und Trigger im Diff-Pfad.
 *
 * Der rote Faden ist, dass Diff- und Generate-Pfad **dasselbe** Urteil faellen.
 * Beim Partitionieren war das einmal nicht so — der Generate-Pfad rendert, der
 * Diff-Pfad schwieg —, und niemand hat es gemerkt, weil beide fuer sich gruen
 * waren.
 */
class MssqlDiffRoutineOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    val calc = FunctionDefinition(
        parameters = listOf(ParameterDefinition("id", "integer")),
        returns = ReturnType("decimal", precision = 10, scale = 2),
        body = "BEGIN RETURN 0 END",
        language = "sql",
        sourceDialect = "mssql",
    )
    val touch = ProcedureDefinition(
        parameters = listOf(ParameterDefinition("id", "integer")),
        body = "BEGIN SELECT 1 END",
        language = "sql",
        sourceDialect = "mssql",
    )
    val audit = TriggerDefinition(
        table = "orders",
        events = setOf(TriggerEvent.INSERT, TriggerEvent.UPDATE),
        timing = TriggerTiming.AFTER,
        forEach = TriggerForEach.STATEMENT,
        body = "BEGIN SET NOCOUNT ON END",
        sourceDialect = "mssql",
    )

    fun schema(
        functions: Map<String, FunctionDefinition> = emptyMap(),
        procedures: Map<String, ProcedureDefinition> = emptyMap(),
        triggers: Map<String, TriggerDefinition> = emptyMap(),
    ) = SchemaDefinition(
        name = "App", version = "1",
        functions = functions, procedures = procedures, triggers = triggers,
    )

    fun plan(diff: SchemaDiff, current: SchemaDefinition, desired: SchemaDefinition): DiffResult =
        planner.plan(current, desired, diff)

    fun up(diff: SchemaDiff, current: SchemaDefinition = schema(), desired: SchemaDefinition = schema()) =
        gen.generateUp(plan(diff, current, desired), DdlGenerationOptions())

    fun down(diff: SchemaDiff, current: SchemaDefinition = schema(), desired: SchemaDefinition = schema()) =
        gen.generateDown(plan(diff, current, desired), DdlGenerationOptions())

    test("Create renders CREATE OR ALTER with the name decoded from the canonical key") {
        val r = up(
            SchemaDiff(
                functionsAdded = listOf(NamedFunction("calc(in:integer)", calc)),
                proceduresAdded = listOf(NamedProcedure("touch(in:integer)", touch)),
                triggersAdded = listOf(NamedTrigger("orders::audit", audit)),
            ),
            desired = schema(
                functions = mapOf("calc(in:integer)" to calc),
                procedures = mapOf("touch(in:integer)" to touch),
                triggers = mapOf("orders::audit" to audit),
            ),
        )
        val sqlText = r.statements.joinToString("\n") { it.sql }
        // Der Key steht im objectRef; emittiert wird der blanke Name.
        sqlText shouldContain "CREATE OR ALTER FUNCTION [calc](@id INT)\nRETURNS DECIMAL(10,2) AS"
        sqlText shouldContain "CREATE OR ALTER PROCEDURE [touch](@id INT) AS"
        sqlText shouldContain "CREATE OR ALTER TRIGGER [audit] ON [orders]\nAFTER INSERT, UPDATE AS"
        r.blockers.shouldBeEmpty()
    }

    test("Create Down drops, Drop Down recreates") {
        val diff = SchemaDiff(functionsAdded = listOf(NamedFunction("calc(in:integer)", calc)))
        down(diff, desired = schema(functions = mapOf("calc(in:integer)" to calc)))
            .statements.single().sql shouldBe "DROP FUNCTION [calc];"

        val removed = SchemaDiff(functionsRemoved = listOf(NamedFunction("calc(in:integer)", calc)))
        up(removed, current = schema(functions = mapOf("calc(in:integer)" to calc)))
            .statements.single().sql shouldBe "DROP FUNCTION [calc];"
        down(removed, current = schema(functions = mapOf("calc(in:integer)" to calc)))
            .statements.single().sql shouldContain "CREATE OR ALTER FUNCTION [calc]"
    }

    // `CREATE OR ALTER` ersetzt in einem Schritt: kein Fenster, in dem die
    // Routine fehlt — dieselbe Ersparnis wie bei den Sichten.
    test("Replace is a single statement in both directions, with the respective body") {
        val newCalc = calc.copy(body = "BEGIN RETURN 1 END")
        val diff = SchemaDiff(
            functionsChanged = listOf(
                FunctionDiff(name = "calc(in:integer)", body = ValueChange(calc.body, newCalc.body)),
            ),
        )
        val current = schema(functions = mapOf("calc(in:integer)" to calc))
        val desired = schema(functions = mapOf("calc(in:integer)" to newCalc))

        val upSql = up(diff, current, desired).statements.single().sql
        upSql shouldContain "BEGIN RETURN 1 END"
        val downSql = down(diff, current, desired).statements.single().sql
        downSql shouldContain "BEGIN RETURN 0 END"
    }

    /** Ein DiffResult um eine einzelne, von Hand gebaute Operation. */
    fun diffOf(op: DiffOperation) = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = listOf(op),
        currentSchema = schema(),
        desiredSchema = schema(),
    )

    // Wie bei den Sichten: `RenameFunction` entsteht nur mit Rename-Overlay,
    // die Aussage haengt aber am Renderer.
    test("Rename uses sp_rename and says that the stored body keeps the old name") {
        val op = DiffOperation.RenameFunction(
            id = "RenameFunction:calc",
            objectRef = DiffObjectRef(DiffObjectType.FUNCTION, listOf("calc(in:integer)")),
            fromName = "calc",
            toName = "calc_v2",
            signature = listOf(ParameterDefinition("id", "integer")),
            bodyHash = null,
            overlaySource = "test",
            overlayEntryId = "e1",
            overlayHash = null,
        )
        val r = gen.generateUp(diffOf(op), DdlGenerationOptions())
        r.statements.single().sql shouldBe "EXEC sp_rename 'calc', 'calc_v2', 'OBJECT';"
        r.diagnostics.map { it.code } listShouldContain "MSSQL_RENAME_KEEPS_ROUTINE_BODY"
        gen.generateDown(diffOf(op), DdlGenerationOptions()).statements.single().sql shouldBe
            "EXEC sp_rename 'calc_v2', 'calc', 'OBJECT';"
    }

    // Ohne Overlay bleibt es Drop + Create — auch das muss sauber rendern.
    test("an unfolded rename renders as drop plus create") {
        val r = up(
            SchemaDiff(
                triggersRemoved = listOf(NamedTrigger("orders::audit", audit)),
                triggersAdded = listOf(NamedTrigger("orders::audit_v2", audit)),
            ),
            current = schema(triggers = mapOf("orders::audit" to audit)),
            desired = schema(triggers = mapOf("orders::audit_v2" to audit)),
        )
        val sqlText = r.statements.joinToString("\n") { it.sql }
        sqlText shouldContain "DROP TRIGGER [audit];"
        sqlText shouldContain "CREATE OR ALTER TRIGGER [audit_v2] ON [orders]"
    }

    // Dieselben Urteile wie im Generate-Pfad — sie kommen aus demselben Code.
    test("what generate refuses, the diff path refuses too") {
        val foreign = calc.copy(sourceDialect = "postgresql")
        up(
            SchemaDiff(functionsAdded = listOf(NamedFunction("calc(in:integer)", foreign))),
            desired = schema(functions = mapOf("calc(in:integer)" to foreign)),
        ).let {
            it.statements.shouldBeEmpty()
            it.diagnostics.single { d -> d.code == "E053" }.message shouldContain "written for 'postgresql'"
            it.primaryBlockedReason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        }

        val rowTrigger = audit.copy(forEach = TriggerForEach.ROW)
        up(
            SchemaDiff(triggersAdded = listOf(NamedTrigger("orders::audit", rowTrigger))),
            desired = schema(triggers = mapOf("orders::audit" to rowTrigger)),
        ).diagnostics.single { it.code == "E053" }.message shouldContain "once per statement"

        val noReturn = FunctionDefinition(body = "BEGIN RETURN 0 END", sourceDialect = "mssql")
        up(
            SchemaDiff(functionsAdded = listOf(NamedFunction("calc()", noReturn))),
            desired = schema(functions = mapOf("calc()" to noReturn)),
        ).diagnostics.single { it.code == "E053" }.message shouldContain "RETURNS clause"
    }

    test("a missing body blocks the rollback as unknown, not as manual work") {
        val bodyless = FunctionDefinition(returns = ReturnType("integer"), sourceDialect = "mssql")
        val r = down(
            SchemaDiff(functionsRemoved = listOf(NamedFunction("calc()", bodyless))),
            current = schema(functions = mapOf("calc()" to bodyless)),
        )
        r.statements.shouldBeEmpty()
        r.diagnostics.single { it.code == "ROUTINE_DOWN_BODY_UNKNOWN" }.message shouldContain "has no body"
        r.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    // Zwei Ueberladungen fallen in T-SQL auf denselben Namen; das zweite
    // `CREATE OR ALTER` ersetzte still das erste.
    test("overloaded routines block in the diff path as well") {
        val calcText = calc.copy(parameters = listOf(ParameterDefinition("id", "text")))
        val desired = schema(
            functions = mapOf("calc(in:integer)" to calc, "calc(in:text)" to calcText),
        )
        val r = up(
            SchemaDiff(
                functionsAdded = listOf(
                    NamedFunction("calc(in:integer)", calc),
                    NamedFunction("calc(in:text)", calcText),
                ),
            ),
            desired = desired,
        )
        r.statements.shouldBeEmpty()
        r.diagnostics.first { it.code == "E053" }.message shouldContain "no routine overloading"
    }
})
