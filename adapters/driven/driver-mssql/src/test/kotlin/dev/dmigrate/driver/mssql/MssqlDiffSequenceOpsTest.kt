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
    fun diffOf(op: DiffOperation, vararg sequences: Pair<String, SequenceDefinition>) = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = listOf(op),
        currentSchema = schema(*sequences),
        desiredSchema = schema(*sequences),
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

    test("an unspecified cache means the server default, not caching turned off") {
        // Live gemessen: `CREATE SEQUENCE` ohne CACHE-Klausel legt die Sequenz
        // mit `is_cached = true` an; ein blankes `ALTER … CACHE` stellt denselben
        // Zustand her, `NO CACHE` schaltet es ab. Sonst baute `migrate` aus
        // derselben Eingabe eine andere Sequenz als `generate`.
        val after = counter.copy(increment = 2)
        val diff = SchemaDiff(
            sequencesChanged = listOf(
                SequenceDiff(name = "sq", increment = ValueChange(counter.increment, after.increment)),
            ),
        )
        val sql = up(diff, schema("sq" to counter), schema("sq" to after)).statements.single().sql
        sql shouldContainStr " CACHE;"
        sql.contains("NO CACHE") shouldBe false
    }

    test("a cycling sequence at its bound wraps — RESTART WITH outside the range is rejected") {
        // Live belegt: „The start value for sequence object must be between the
        // minimum and maximum value". `RESTART WITH` bricht nicht selbst um.
        val cycling = SequenceDefinition(start = 1, increment = 1, minValue = 1, maxValue = 5, cycle = true)
        val ref = SequenceObjectRef(name = "cyc", schema = null, dialect = RenameProjectionDialect.POSTGRESQL)
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "AlterSequenceCurrentValue:cyc",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("cyc")),
            pairId = "p1",
            probeSequenceRef = ref,
            applySequenceRef = ref,
            currentValue = 5,
        )
        gen.generateUp(diffOf(op, "cyc" to cycling), DdlGenerationOptions())
            .statements.single().sql shouldBe "ALTER SEQUENCE [cyc] RESTART WITH 1;"
    }

    test("an exhausted non-cycling sequence blocks instead of rendering a rejected statement") {
        val bounded = SequenceDefinition(start = 1, increment = 1, minValue = 1, maxValue = 5, cycle = false)
        val ref = SequenceObjectRef(name = "fin", schema = null, dialect = RenameProjectionDialect.POSTGRESQL)
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "AlterSequenceCurrentValue:fin",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("fin")),
            pairId = "p1",
            probeSequenceRef = ref,
            applySequenceRef = ref,
            currentValue = 5,
        )
        val r = gen.generateUp(diffOf(op, "fin" to bounded), DdlGenerationOptions())
        r.statements.isEmpty() shouldBe true
        r.diagnostics.map { it.code } shouldContain "MSSQL_SEQUENCE_EXHAUSTED"
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
        val r = gen.generateUp(diffOf(op, "sq" to counter), DdlGenerationOptions())
        r.statements.single().sql shouldBe "ALTER SEQUENCE [sq] RESTART WITH 42;"
        // RESTART WITH schreibt auch start_value um — der Reverse sieht das.
        r.diagnostics.map { it.code } shouldContain "MSSQL_RESTART_REWRITES_START"
        gen.generateDown(diffOf(op, "sq" to counter), DdlGenerationOptions()).statements.single().sql shouldBe
            "ALTER SEQUENCE [sq] RESTART WITH 8;"
    }

    test("a descending sequence resumes DOWNWARDS — a step of 1 would reissue values") {
        // `INCREMENT BY -1`, zuletzt ausgegeben 5: der naechste Wert ist 4.
        // `RESTART WITH 6` gaebe 6 und 5 ein zweites Mal aus.
        val descending = SequenceDefinition(start = 100, increment = -1)
        val ref = SequenceObjectRef(name = "down", schema = null, dialect = RenameProjectionDialect.POSTGRESQL)
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "AlterSequenceCurrentValue:down",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("down")),
            pairId = "p1",
            probeSequenceRef = ref,
            applySequenceRef = ref,
            currentValue = 5,
        )
        gen.generateUp(diffOf(op, "down" to descending), DdlGenerationOptions())
            .statements.single().sql shouldBe "ALTER SEQUENCE [down] RESTART WITH 4;"
    }

    test("a wider step keeps the sequence on its stride") {
        val byFive = SequenceDefinition(start = 10, increment = 5)
        val ref = SequenceObjectRef(name = "five", schema = null, dialect = RenameProjectionDialect.POSTGRESQL)
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "AlterSequenceCurrentValue:five",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("five")),
            pairId = "p1",
            probeSequenceRef = ref,
            applySequenceRef = ref,
            currentValue = 20,
        )
        gen.generateUp(diffOf(op, "five" to byFive), DdlGenerationOptions())
            .statements.single().sql shouldBe "ALTER SEQUENCE [five] RESTART WITH 25;"
    }

    test("without the sequence in the schema the resume point is blocked, not guessed") {
        val ref = SequenceObjectRef(name = "ghost", schema = null, dialect = RenameProjectionDialect.POSTGRESQL)
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "AlterSequenceCurrentValue:ghost",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("ghost")),
            pairId = "p1",
            probeSequenceRef = ref,
            applySequenceRef = ref,
            currentValue = 5,
        )
        val r = gen.generateUp(diffOf(op), DdlGenerationOptions())
        r.statements.isEmpty() shouldBe true
        r.diagnostics.map { it.code } shouldContain "MSSQL_COLUMN_NOT_IN_SCHEMA"
    }

    test("a DESCENDING cycling sequence wraps to its maximum, not to its minimum") {
        val descendingCycle = SequenceDefinition(
            start = 5, increment = -1, minValue = 1, maxValue = 5, cycle = true,
        )
        val ref = SequenceObjectRef(name = "dc", schema = null, dialect = RenameProjectionDialect.POSTGRESQL)
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "AlterSequenceCurrentValue:dc",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("dc")),
            pairId = "p1",
            probeSequenceRef = ref,
            applySequenceRef = ref,
            currentValue = 1,
        )
        gen.generateUp(diffOf(op, "dc" to descendingCycle), DdlGenerationOptions())
            .statements.single().sql shouldBe "ALTER SEQUENCE [dc] RESTART WITH 5;"
    }

    test("an unbounded sequence at the type limit does not overflow into a wrong value") {
        // `Long.MAX_VALUE + 1` liefe ueber; ohne CYCLE gibt es keinen
        // Fortsetzungspunkt, und geraten wird nicht.
        val unbounded = SequenceDefinition(start = 1, increment = 1)
        val ref = SequenceObjectRef(name = "big", schema = null, dialect = RenameProjectionDialect.POSTGRESQL)
        val op = DiffOperation.AlterSequenceCurrentValue(
            id = "AlterSequenceCurrentValue:big",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("big")),
            pairId = "p1",
            probeSequenceRef = ref,
            applySequenceRef = ref,
            currentValue = Long.MAX_VALUE,
        )
        val r = gen.generateUp(diffOf(op, "big" to unbounded), DdlGenerationOptions())
        r.statements.isEmpty() shouldBe true
        r.diagnostics.map { it.code } shouldContain "MSSQL_SEQUENCE_EXHAUSTED"
    }
})
