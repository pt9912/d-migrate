package dev.dmigrate.driver.mysql

import dev.dmigrate.core.diff.SchemaDiff
import dev.dmigrate.core.diff.migration.DiffEndpoint
import dev.dmigrate.core.diff.migration.DiffObjectRef
import dev.dmigrate.core.diff.migration.DiffObjectType
import dev.dmigrate.core.diff.migration.DiffOperation
import dev.dmigrate.core.diff.migration.DiffResult
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DdlGenerationOptions
import dev.dmigrate.driver.MysqlNamedSequenceMode
import dev.dmigrate.driver.MysqlSequenceSupportNaming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * 0.9.7 preserve-current-value Sub-Slice A: pins the MySQL renderer
 * for `AlterSequenceCurrentValue`. The planner-emit path lands in
 * Sub-Slice D, so this test builds the [DiffResult] by hand and
 * feeds it through `MysqlDiffDdlGenerator` directly — the renderer
 * is the unit under test, not the planner.
 */
class MysqlDiffSequenceOpsPreserveCurrentValueTest : FunSpec({

    val gen = MysqlDiffDdlGenerator()
    val helperOptions = DdlGenerationOptions(
        mysqlNamedSequenceMode = MysqlNamedSequenceMode.HELPER_TABLE,
    )

    fun synthesiseDiff(op: DiffOperation.AlterSequenceCurrentValue): DiffResult = DiffResult(
        current = DiffEndpoint("App", "1", "fp-current"),
        desired = DiffEndpoint("App", "1", "fp-desired"),
        schemaDiff = SchemaDiff(),
        operations = listOf(op),
    )

    fun mysqlRef(name: String) = SequenceObjectRef(name, null, RenameProjectionDialect.MYSQL)

    fun preserveOp(
        sequenceName: String = "order_seq",
        currentValue: Long = 42L,
        restoreValue: Long? = 10L,
        rollbackImpossible: Boolean = false,
        rollbackImpossibleReason: String? = null,
        applyName: String = sequenceName,
        probeName: String = sequenceName,
    ) = DiffOperation.AlterSequenceCurrentValue(
        id = "acv-$sequenceName",
        objectRef = DiffObjectRef(DiffObjectType.SEQUENCE, listOf(sequenceName)),
        pairId = "alter:$sequenceName",
        probeSequenceRef = mysqlRef(probeName),
        applySequenceRef = mysqlRef(applyName),
        currentValue = currentValue,
        isCalled = null, // MySQL helper-table doesn't use isCalled
        restoreValue = restoreValue,
        restoreIsCalled = null,
        rollbackImpossible = rollbackImpossible,
        rollbackImpossibleReason = rollbackImpossibleReason,
    )

    test("Up emits UPDATE dmg_sequences SET next_value = <v> with managed_by + format_version filter") {
        val up = gen.generateUp(synthesiseDiff(preserveOp()), helperOptions)
        up.isBlocked shouldBe false
        val sql = up.statements.single().sql
        sql shouldContain "UPDATE `dmg_sequences` SET `next_value` = 42 "
        sql shouldContain "`name` = 'order_seq'"
        sql shouldContain "`managed_by` = 'd-migrate'"
        sql shouldContain "`format_version` IN ('mysql-sequence-v1')"
        // 1-row determinism note in §5.4: trailing semicolon present.
        sql.endsWith(";") shouldBe true
    }

    test("Down emits UPDATE on probeSequenceRef with restoreValue") {
        val down = gen.generateDown(
            synthesiseDiff(preserveOp(
                applyName = "new_seq",
                probeName = "old_seq",
                currentValue = 42L,
                restoreValue = 5L,
            )),
            helperOptions,
        )
        val sql = down.statements.single().sql
        sql shouldContain "`next_value` = 5 "
        sql shouldContain "`name` = 'old_seq'"
    }

    test("Down skipped as comment when rollbackImpossible — no half-built UPDATE") {
        val down = gen.generateDown(
            synthesiseDiff(preserveOp(
                rollbackImpossible = true,
                rollbackImpossibleReason = "new sequence has no prior state",
                restoreValue = null,
            )),
            helperOptions,
        )
        val sql = down.statements.single().sql
        sql shouldContain "preserve-current-value down skipped"
        sql shouldContain "new sequence has no prior state"
        sql shouldNotContain "UPDATE"
    }

    test("Down skipped when restoreValue is null even without explicit rollbackImpossible flag") {
        val down = gen.generateDown(
            synthesiseDiff(preserveOp(restoreValue = null)),
            helperOptions,
        )
        down.statements.single().sql shouldContain "no deterministic restore snapshot"
    }

    test("sequence name literal is single-quote-escaped (no SQL injection through identifier)") {
        val up = gen.generateUp(
            synthesiseDiff(preserveOp(sequenceName = "weird'name", applyName = "weird'name", probeName = "weird'name")),
            helperOptions,
        )
        up.statements.single().sql shouldContain "`name` = 'weird''name'"
    }

    test("non-HELPER_TABLE mode → blocker, no UPDATE emitted") {
        val actionRequiredOptions = DdlGenerationOptions(
            mysqlNamedSequenceMode = MysqlNamedSequenceMode.ACTION_REQUIRED,
        )
        val up = gen.generateUp(synthesiseDiff(preserveOp()), actionRequiredOptions)
        // Mode gate emits a blocker; statements must not carry an UPDATE
        // against the helper table when the mode is not HELPER_TABLE.
        up.statements.none { it.sql.contains("dmg_sequences") } shouldBe true
        up.isBlocked shouldBe true
    }

    test("format_version IN-list iterates SUPPORTED_FORMAT_VERSIONS in declaration order") {
        // The constant SUPPORTED_FORMAT_VERSIONS holds the source of
        // truth; the renderer's IN-list must match it so the
        // Sub-Slice C probe (which filters with the same set) and
        // the renderer cannot drift.
        val expected = MysqlSequenceSupportNaming.SUPPORTED_FORMAT_VERSIONS
            .joinToString(", ") { "'$it'" }
        val up = gen.generateUp(synthesiseDiff(preserveOp()), helperOptions)
        up.statements.single().sql shouldContain "IN ($expected)"
    }

    test("mysqlSequenceLookupKey is consistent with renderer's name-literal source") {
        // The same SequenceObjectRef → lookup key function is meant
        // to be used by the Sub-Slice C probe. Today it returns the
        // bare name; pin that so the probe stays compatible.
        val ref = mysqlRef("order_seq")
        MysqlSequenceSupportNaming.lookupKey(ref) shouldBe "order_seq"
    }
})
