package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DdlGenerationOptions
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * 0.9.7 preserve-current-value Sub-Slice A: pins the PG renderer for
 * `AlterSequenceCurrentValue`. The planner-emit path lands in
 * Sub-Slice D, so this test builds the [DiffResult] by hand and feeds
 * it through `PostgresDiffDdlGenerator` directly — the unit under
 * test is the renderer, not the planner.
 */
class PostgresDiffSequenceOpsPreserveCurrentValueTest : FunSpec({

    val gen = PostgresDiffDdlGenerator()

    fun synthesiseDiff(op: DiffOperation.AlterSequenceCurrentValue): DiffResult = DiffResult(
        current = DiffEndpoint("App", "1", "fp-current"),
        desired = DiffEndpoint("App", "1", "fp-desired"),
        schemaDiff = SchemaDiff(),
        operations = listOf(op),
    )

    fun pgRef(name: String) = SequenceObjectRef(name, null, RenameProjectionDialect.POSTGRESQL)

    fun preserveOp(
        sequenceName: String = "order_seq",
        currentValue: Long = 42L,
        isCalled: Boolean? = true,
        restoreValue: Long? = 10L,
        restoreIsCalled: Boolean? = true,
        rollbackImpossible: Boolean = false,
        rollbackImpossibleReason: String? = null,
        applyName: String = sequenceName,
        probeName: String = sequenceName,
    ) = DiffOperation.AlterSequenceCurrentValue(
        id = "acv-$sequenceName",
        objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(sequenceName)),
        pairId = "alter:$sequenceName",
        probeSequenceRef = pgRef(probeName),
        applySequenceRef = pgRef(applyName),
        currentValue = currentValue,
        isCalled = isCalled,
        restoreValue = restoreValue,
        restoreIsCalled = restoreIsCalled,
        rollbackImpossible = rollbackImpossible,
        rollbackImpossibleReason = rollbackImpossibleReason,
    )

    test("Up emits SELECT setval('<seq>', <value>, <isCalled>);") {
        val up = gen.generateUp(synthesiseDiff(preserveOp()), DdlGenerationOptions())
        up.isBlocked shouldBe false
        up.statements.single().sql shouldBe "SELECT setval('order_seq', 42, true);"
    }

    test("Up with isCalled=false propagates verbatim — affects nextval semantics") {
        val up = gen.generateUp(
            synthesiseDiff(preserveOp(currentValue = 100L, isCalled = false)),
            DdlGenerationOptions(),
        )
        up.statements.single().sql shouldBe "SELECT setval('order_seq', 100, false);"
    }

    test("Down emits setval on probeSequenceRef with restoreValue + restoreIsCalled") {
        val down = gen.generateDown(
            synthesiseDiff(preserveOp(
                applyName = "new_seq",
                probeName = "old_seq",
                currentValue = 42L,
                restoreValue = 5L,
                restoreIsCalled = false,
            )),
            DdlGenerationOptions(),
        )
        down.statements.single().sql shouldBe "SELECT setval('old_seq', 5, false);"
    }

    test("Down skipped as comment when rollbackImpossible — no half-built setval(NULL)") {
        val down = gen.generateDown(
            synthesiseDiff(preserveOp(
                rollbackImpossible = true,
                rollbackImpossibleReason = "new sequence has no prior state",
                restoreValue = null,
                restoreIsCalled = null,
            )),
            DdlGenerationOptions(),
        )
        val sql = down.statements.single().sql
        sql shouldContain "preserve-current-value down skipped"
        sql shouldContain "new sequence has no prior state"
        sql shouldNotContain "setval"
    }

    test("Down skipped when restoreValue is null even without explicit rollbackImpossible flag") {
        val down = gen.generateDown(
            synthesiseDiff(preserveOp(restoreValue = null, restoreIsCalled = null)),
            DdlGenerationOptions(),
        )
        down.statements.single().sql shouldContain "no deterministic restore snapshot"
    }

    test("Up requires non-null isCalled — PG cannot render setval without it") {
        // The Sub-Slice D planner-emit gate is supposed to refuse
        // emitting `AlterSequenceCurrentValue` on PG without
        // `isCalled`, but the renderer enforces it too so a
        // planner regression surfaces with a clear message instead
        // of a half-built `setval(seq, value, null)`.
        val opWithoutIsCalled = preserveOp(isCalled = null)
        try {
            gen.generateUp(synthesiseDiff(opWithoutIsCalled), DdlGenerationOptions())
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            e.message!!.shouldContain("isCalled")
        }
    }

    test("string literal is single-quote-escaped via SqlIdentifiers.quoteStringLiteral") {
        // An operator-supplied sequence name with an embedded
        // apostrophe MUST not break out of the literal — pin the
        // doubled-quote escape so a future renderer-internal change
        // can't accidentally use raw interpolation.
        val up = gen.generateUp(
            synthesiseDiff(preserveOp(sequenceName = "weird'name", applyName = "weird'name", probeName = "weird'name")),
            DdlGenerationOptions(),
        )
        up.statements.single().sql shouldBe "SELECT setval('weird''name', 42, true);"
    }
})
