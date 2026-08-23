package dev.dmigrate.driver.mssql

import dev.dmigrate.core.diff.NamedSequence
import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.SequenceDiff
import dev.dmigrate.core.diff.ValueChange
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffPlanner
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.core.model.SequenceDefinition
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

/**
 * Sub-Slice 5d: Sequenzen.
 *
 * SQL Server hat native Sequenzen, der Diff-Pfad liegt damit nahe an
 * PostgreSQL. Die Faelle hier halten fest, wo T-SQL trotzdem eigene Regeln
 * hat: `ALTER SEQUENCE` kann den Startwert nicht aendern, umbenannt wird ueber
 * `sp_rename`, und `RESTART WITH` setzt den naechsten auszugebenden Wert.
 */
class MssqlDiffSequenceOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = MssqlDiffDdlGenerator()

    val counter = SequenceDefinition(start = 10, increment = 1)

    fun schema(vararg sequences: Pair<String, SequenceDefinition>) =
        SchemaDefinition(name = "App", version = "1", sequences = sequences.toMap())

    fun up(diff: SchemaDiff, current: SchemaDefinition = schema(), desired: SchemaDefinition = schema()) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun down(diff: SchemaDiff, current: SchemaDefinition = schema(), desired: SchemaDefinition = schema()) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    /** Ein DiffResult um eine von Hand gebaute Operation. */
    fun diffOf(op: DiffOperation) = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = listOf(op),
        currentSchema = schema(),
        desiredSchema = schema(),
    )

    test("CreateSequence renders the native form; down drops it") {
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("sq", counter)))
        up(diff, schema(), schema("sq" to counter)).statements.single().sql shouldBe
            "CREATE SEQUENCE [sq] AS BIGINT START WITH 10 INCREMENT BY 1 NO CYCLE;"
        down(diff, schema(), schema("sq" to counter)).statements.single().sql shouldBe "DROP SEQUENCE [sq];"
    }

    test("DropSequence removes it upwards and re-creates it downwards") {
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("sq", counter)))
        up(diff, schema("sq" to counter), schema()).statements.single().sql shouldBe "DROP SEQUENCE [sq];"
        down(diff, schema("sq" to counter), schema()).statements.single().sql shouldContainStr "CREATE SEQUENCE [sq]"
    }

    test("AlterSequence spells out every bound — leaving one off would keep the old value") {
        val after = counter.copy(increment = 5, cycle = true, cache = 20)
        val diff = SchemaDiff(
            sequencesChanged = listOf(
                SequenceDiff(
                    name = "sq",
                    increment = ValueChange(counter.increment, after.increment),
                    cycle = ValueChange(counter.cycle, after.cycle),
                    cache = ValueChange(counter.cache, after.cache),
                ),
            ),
        )
        val sql = up(diff, schema("sq" to counter), schema("sq" to after)).statements.single().sql
        sql shouldBe "ALTER SEQUENCE [sq] INCREMENT BY 5 MINVALUE 1 NO MAXVALUE CYCLE CACHE 20;"
    }

    test("a changed start is reported, not silently dropped — T-SQL cannot alter it") {
        val after = counter.copy(start = 500)
        val diff = SchemaDiff(
            sequencesChanged = listOf(SequenceDiff(name = "sq", start = ValueChange(counter.start, after.start))),
        )
        val r = up(diff, schema("sq" to counter), schema("sq" to after))
        r.diagnostics.map { it.code } shouldContain "MSSQL_SEQUENCE_START_IMMUTABLE"
        // Die uebrigen Attribute werden trotzdem angewandt.
        r.statements.single().sql shouldContainStr "ALTER SEQUENCE [sq]"
    }

    test("renaming uses sp_rename — T-SQL has no ALTER SEQUENCE RENAME TO") {
        val op = DiffOperation.RenameSequence(
            id = "RenameSequence:sq",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("sq")),
            fromName = "sq",
            toName = "sq_new",
            overlaySource = "test",
            overlayEntryId = "e1",
            overlayHash = null,
        )
        gen.generateUp(diffOf(op), DdlGenerationOptions()).statements.single().sql shouldBe
            "EXEC sp_rename 'sq', 'sq_new';"
        gen.generateDown(diffOf(op), DdlGenerationOptions()).statements.single().sql shouldBe
            "EXEC sp_rename 'sq_new', 'sq';"
    }

    test("preserving the current value restarts one step ahead — the safe direction") {
        // Live gemessen: `current_value` ist der zuletzt ausgegebene Wert, bei
        // einer nie benutzten Sequenz aber der Startwert — beides ist nicht zu
        // unterscheiden. `RESTART WITH x` setzt den NAECHSTEN Wert auf x, also
        // wird bei Wert+1 fortgesetzt: hoechstens ein uebersprungener Schluessel,
        // nie ein doppelt vergebener.
        //
        // `dialect` ist hier ein Platzhalter: der MSSQL-Renderer liest das Feld
        // nicht, und `RenameProjectionDialect` bekommt seinen MSSQL-Eintrag erst
        // mit Sub-Slice 5e.
        val ref = SequenceObjectRef(name = "sq", schema = null, dialect = RenameProjectionDialect.POSTGRESQL)
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "AlterSequenceCurrentValue:sq",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("sq")),
            pairId = "p1",
            probeSequenceRef = ref,
            applySequenceRef = ref,
            currentValue = 41,
            restoreValue = 7,
        )
        val r = gen.generateUp(diffOf(op), DdlGenerationOptions())
        r.statements.single().sql shouldBe "ALTER SEQUENCE [sq] RESTART WITH 42;"
        // RESTART WITH schreibt auch start_value um — der Reverse sieht das.
        r.diagnostics.map { it.code } shouldContain "MSSQL_RESTART_REWRITES_START"
        gen.generateDown(diffOf(op), DdlGenerationOptions()).statements.single().sql shouldBe
            "ALTER SEQUENCE [sq] RESTART WITH 8;"
    }
})
