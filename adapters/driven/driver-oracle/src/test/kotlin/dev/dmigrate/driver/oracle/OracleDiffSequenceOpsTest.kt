package dev.dmigrate.driver.oracle

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
import dev.dmigrate.driver.migration.LockBehavior
import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Sub-Slice 5d: Sequenzen. Die Erwartungen beruhen auf live gemessenen
 * Oracle-Eigenheiten (siehe [OracleSequenceDdl] und
 * [OracleDiffSequenceOps]): `START WITH` ist unveraenderlich, der
 * Laufzeitstand wird mit `RESTART START WITH` gesetzt, und `LAST_NUMBER`
 * meint den NAECHSTEN Wert — nicht den zuletzt ausgegebenen wie in T-SQL.
 */
class OracleDiffSequenceOpsTest : FunSpec({

    val planner = DiffPlanner()
    val gen = OracleDiffDdlGenerator()

    val seq = SequenceDefinition(start = 1, increment = 1)

    fun schemaWith(name: String, s: SequenceDefinition) =
        SchemaDefinition(name = "App", version = "1", sequences = mapOf(name to s))

    fun emptySchema() = SchemaDefinition(name = "App", version = "1")

    fun up(diff: SchemaDiff, current: SchemaDefinition = emptySchema(), desired: SchemaDefinition = emptySchema()) =
        gen.generateUp(planner.plan(current, desired, diff), DdlGenerationOptions())

    fun down(diff: SchemaDiff, current: SchemaDefinition = emptySchema(), desired: SchemaDefinition = emptySchema()) =
        gen.generateDown(planner.plan(current, desired, diff), DdlGenerationOptions())

    test("CreateSequence writes every clause; down drops it") {
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("s_orders", seq)))
        up(diff).statements.single().sql shouldBe
            "CREATE SEQUENCE \"s_orders\" START WITH 1 INCREMENT BY 1 NOMINVALUE NOMAXVALUE NOCYCLE NOCACHE;"
        down(diff).statements.single().sql shouldBe "DROP SEQUENCE \"s_orders\";"
    }

    test("CreateSequence renders the bounded clauses when the model carries them") {
        val bounded = SequenceDefinition(start = 5, increment = 2, minValue = 1, maxValue = 99, cycle = true, cache = 20)
        val diff = SchemaDiff(sequencesAdded = listOf(NamedSequence("s_b", bounded)))
        up(diff).statements.single().sql shouldBe
            "CREATE SEQUENCE \"s_b\" START WITH 5 INCREMENT BY 2 MINVALUE 1 MAXVALUE 99 CYCLE CACHE 20;"
    }

    test("DropSequence: up drops, down recreates from the carried definition") {
        val diff = SchemaDiff(sequencesRemoved = listOf(NamedSequence("s_orders", seq)))
        up(diff).statements.single().sql shouldBe "DROP SEQUENCE \"s_orders\";"
        down(diff).statements.single().sql shouldBe
            "CREATE SEQUENCE \"s_orders\" START WITH 1 INCREMENT BY 1 NOMINVALUE NOMAXVALUE NOCYCLE NOCACHE;"
    }

    test("AlterSequence writes every clause but never START WITH — Oracle cannot change it") {
        val after = SequenceDefinition(start = 1, increment = 5, maxValue = 500, cache = 10)
        val diff = SchemaDiff(
            sequencesChanged = listOf(
                SequenceDiff(name = "s_orders", increment = ValueChange(1L, 5L), maxValue = ValueChange(null, 500L)),
            ),
        )
        val sql = up(diff, schemaWith("s_orders", seq), schemaWith("s_orders", after)).statements.single().sql
        sql shouldBe "ALTER SEQUENCE \"s_orders\" INCREMENT BY 5 NOMINVALUE MAXVALUE 500 NOCYCLE CACHE 10;"
    }

    test("a start change is reported, not rendered — and the note names the reverse-read cause") {
        val after = SequenceDefinition(start = 900, increment = 1)
        val diff = SchemaDiff(sequencesChanged = listOf(SequenceDiff(name = "s_orders", start = ValueChange(1L, 900L))))
        val r = up(diff, schemaWith("s_orders", seq), schemaWith("s_orders", after))
        r.statements.single().sql shouldBe
            "ALTER SEQUENCE \"s_orders\" INCREMENT BY 1 NOMINVALUE NOMAXVALUE NOCYCLE NOCACHE;"
        val note = r.diagnostics.single { it.code == "ORACLE_SEQUENCE_START_IMMUTABLE" }
        note.message shouldBe
            "Sequence 's_orders' changes its start value from 1 to 900, but Oracle cannot alter the starting " +
            "number of an existing sequence (ORA-02283); the rendered ALTER SEQUENCE leaves it untouched. " +
            "Note that Oracle's reverse read reports the current LAST_NUMBER as the start value (R345), so a " +
            "sequence that has ever been drawn from will show this difference without the model having changed."
    }

    test("RenameSequence uses the standalone RENAME, like views") {
        val op = DiffOperation.RenameSequence(
            id = "rename-seq",
            objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("s_new")),
            fromName = "s_old",
            toName = "s_new",
            overlaySource = "ovl/rename.json",
            overlayEntryId = "s_old->s_new",
            overlayHash = null,
        )
        val plan = DiffResult(
            current = DiffEndpoint(schemaName = "App"),
            desired = DiffEndpoint(schemaName = "App"),
            schemaDiff = SchemaDiff(),
            operations = listOf(op),
        )
        gen.generateUp(plan, DdlGenerationOptions()).statements.single().sql shouldBe
            "RENAME \"s_old\" TO \"s_new\";"
        gen.generateDown(plan, DdlGenerationOptions()).statements.single().sql shouldBe
            "RENAME \"s_new\" TO \"s_old\";"
    }

    // ── AlterSequenceCurrentValue ────────────────

    /**
     * Probe- und Apply-Referenz tragen ABSICHTLICH verschiedene Namen (die
     * Form eines Sequenz-Renames): sonst koennte man UP und DOWN vertauschen,
     * ohne dass ein Test es merkt.
     */
    fun currentValueOp(value: Long, restore: Long? = null) = DiffOperation.AlterSequenceCurrentValue(
        id = "seq-current",
        objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf("s_new")),
        pairId = "rename:s_old->s_new",
        probeSequenceRef = SequenceObjectRef(name = "s_old", dialect = RenameProjectionDialect.ORACLE),
        applySequenceRef = SequenceObjectRef(name = "s_new", dialect = RenameProjectionDialect.ORACLE),
        currentValue = value,
        restoreValue = restore,
    )

    fun planWith(op: DiffOperation, schema: SchemaDefinition?) = DiffResult(
        current = DiffEndpoint(schemaName = "App"),
        desired = DiffEndpoint(schemaName = "App"),
        schemaDiff = SchemaDiff(),
        operations = listOf(op),
        currentSchema = schema,
        desiredSchema = schema,
    )

    test("AlterSequenceCurrentValue resumes AT the probed value, on the APPLY reference") {
        // Die T-SQL-Arithmetik (Wert + Schrittweite) waere hier falsch:
        // Oracles LAST_NUMBER meint bereits den naechsten freien Wert.
        val schema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("s_old" to seq, "s_new" to seq),
        )
        val r = gen.generateUp(planWith(currentValueOp(21), schema), DdlGenerationOptions())
        r.statements.single().sql shouldBe "ALTER SEQUENCE \"s_new\" RESTART START WITH 21;"
    }

    test("down uses the restore value on the PROBE reference; without one it blocks the rollback") {
        val schema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("s_old" to seq, "s_new" to seq),
        )
        val withRestore = gen.generateDown(
            planWith(currentValueOp(21, restore = 7), schema),
            DdlGenerationOptions(),
        )
        withRestore.statements.single().sql shouldBe "ALTER SEQUENCE \"s_old\" RESTART START WITH 7;"

        val withoutRestore = gen.generateDown(
            planWith(currentValueOp(21), schema),
            DdlGenerationOptions(),
        )
        withoutRestore.statements.shouldBeEmpty()
        withoutRestore.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("without the sequence definition it blocks instead of guessing the bounds") {
        val r = gen.generateUp(planWith(currentValueOp(21), null), DdlGenerationOptions())
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_SEQUENCE_NOT_IN_SCHEMA" } shouldBe true
    }

    test("a resume point outside MINVALUE/MAXVALUE blocks") {
        val bounded = SequenceDefinition(start = 1, increment = 1, minValue = 1, maxValue = 100)
        val schema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("s_old" to bounded, "s_new" to bounded),
        )
        val r = gen.generateUp(planWith(currentValueOp(9999), schema), DdlGenerationOptions())
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_SEQUENCE_EXHAUSTED" } shouldBe true
    }

    test("the atomic-preserve sentinel is not rendered as a resume point") {
        val schema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("s_old" to seq, "s_new" to seq),
        )
        val sentinel = DiffOperation.AlterSequenceCurrentValue.ATOMIC_PRESERVE_SENTINEL_CURRENT_VALUE
        val r = gen.generateUp(planWith(currentValueOp(sentinel), schema), DdlGenerationOptions())
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_SEQUENCE_PRESERVE_SENTINEL" } shouldBe true
    }

    test("an undeclared MINVALUE means 1 on an ascending sequence, not unbounded") {
        val schema = SchemaDefinition(
            name = "App", version = "1",
            sequences = mapOf("s_old" to seq, "s_new" to seq),
        )
        val r = gen.generateUp(planWith(currentValueOp(-5), schema), DdlGenerationOptions())
        r.statements.shouldBeEmpty()
        r.diagnostics.any { it.code == "ORACLE_SEQUENCE_EXHAUSTED" } shouldBe true
    }

    test("AlterSequence DOWN renders the before state and reports the start change") {
        val after = SequenceDefinition(start = 900, increment = 5)
        val diff = SchemaDiff(
            sequencesChanged = listOf(
                SequenceDiff(name = "s_orders", start = ValueChange(1L, 900L), increment = ValueChange(1L, 5L)),
            ),
        )
        val r = down(diff, schemaWith("s_orders", seq), schemaWith("s_orders", after))
        r.statements.single().sql shouldBe
            "ALTER SEQUENCE \"s_orders\" INCREMENT BY 1 NOMINVALUE NOMAXVALUE NOCYCLE NOCACHE;"
        r.diagnostics.single { it.code == "ORACLE_SEQUENCE_START_IMMUTABLE" }
            .message shouldContain "from 900 to 1"
    }

    test("no start warning when the start is unchanged, and the plan is not blocked") {
        val after = SequenceDefinition(start = 1, increment = 5)
        val diff = SchemaDiff(sequencesChanged = listOf(SequenceDiff(name = "s_orders", increment = ValueChange(1L, 5L))))
        val r = up(diff, schemaWith("s_orders", seq), schemaWith("s_orders", after))
        r.diagnostics.none { it.code == "ORACLE_SEQUENCE_START_IMMUTABLE" } shouldBe true
        r.isBlocked shouldBe false
    }

    test("sequence DDL is metadata-only") {
        val stmt = up(SchemaDiff(sequencesAdded = listOf(NamedSequence("s_orders", seq)))).statements.single()
        stmt.hints.lockBehavior shouldBe LockBehavior.METADATA
        stmt.hints.requiresExclusiveAccess shouldBe false
    }

    test("the Generate path and the Diff path emit byte-identical CREATE SEQUENCE") {
        // Beide gehen durch OracleSequenceDdl; eine zweite Fassung wuerde
        // frueher oder spaeter an einer Klausel auseinanderlaufen.
        val generated = OracleDdlGenerator()
            .generate(schemaWith("s_orders", seq))
            .statements.map { it.sql }
            .single { it.startsWith("CREATE SEQUENCE") }
        val diffed = up(SchemaDiff(sequencesAdded = listOf(NamedSequence("s_orders", seq)))).statements.single().sql
        diffed shouldBe generated
    }
})
