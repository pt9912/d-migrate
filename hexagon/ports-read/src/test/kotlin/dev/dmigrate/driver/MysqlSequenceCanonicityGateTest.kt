package dev.dmigrate.driver

import dev.dmigrate.driver.migration.MigrationBlockedReason
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * E.3 MySQL Sequence Drift-Check Sub-Slice B: pins the gate's
 * decision matrix per (status × kind × op-intent). The renderer
 * (Sub-Slice D) and the CLI stage (Sub-Slice C) consult this
 * gate via [MysqlSequenceCanonicityGate.decide]; the tests below
 * cover every Status branch and every meaningful (kind, intent)
 * combination so the gate's routing contract is reviewable in
 * one place.
 */
class MysqlSequenceCanonicityGateTest : FunSpec({

    fun decl(
        status: MysqlSequenceCanonicityStatus,
        kind: MysqlSequenceCanonicityKind,
        objectName: String = "order_seq",
        driftField: String? = null,
        expected: String? = null,
        actual: String? = null,
        problem: String? = null,
    ) = MysqlSequenceCanonicityDeclaration(
        operationId = "op-1",
        dialect = "mysql",
        kind = kind,
        objectName = objectName,
        status = status,
        sqlHash = "abc",
        driftField = driftField,
        expected = expected,
        actual = actual,
        problem = problem,
    )

    // ── CANONICAL → Proceed regardless of kind / intent ──────────

    test("CANONICAL status proceeds for every intent") {
        for (intent in MysqlSequenceCanonicityGate.OpIntent.entries) {
            for (kind in MysqlSequenceCanonicityKind.entries) {
                MysqlSequenceCanonicityGate.decide(decl(MysqlSequenceCanonicityStatus.CANONICAL, kind), intent) shouldBe
                    MysqlSequenceCanonicityGate.Decision.Proceed
            }
        }
    }

    // ── DRIFT → Block with kind-specific code ──────────────────

    test("DRIFT routes to per-kind code") {
        val table = decl(MysqlSequenceCanonicityStatus.DRIFT,
            MysqlSequenceCanonicityKind.SUPPORT_TABLE, driftField = "column_type[increment_by]",
            expected = "bigint", actual = "int")
        val tableDecision = MysqlSequenceCanonicityGate.decide(table, MysqlSequenceCanonicityGate.OpIntent.CREATE)
            .shouldBeInstanceOf<MysqlSequenceCanonicityGate.Decision.Block>()
        tableDecision.code shouldBe MysqlSequenceCanonicityGate.DRIFT_TABLE_CODE
        tableDecision.reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        tableDecision.message shouldContain "column_type[increment_by]"

        val routine = decl(MysqlSequenceCanonicityStatus.DRIFT,
            MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE, driftField = "body_marker")
        (MysqlSequenceCanonicityGate.decide(routine, MysqlSequenceCanonicityGate.OpIntent.CREATE)
            as MysqlSequenceCanonicityGate.Decision.Block)
            .code shouldBe MysqlSequenceCanonicityGate.DRIFT_ROUTINE_CODE

        val row = decl(MysqlSequenceCanonicityStatus.DRIFT,
            MysqlSequenceCanonicityKind.SEQUENCE_ROW, driftField = "increment_by",
            expected = "1", actual = "5")
        (MysqlSequenceCanonicityGate.decide(row, MysqlSequenceCanonicityGate.OpIntent.ALTER)
            as MysqlSequenceCanonicityGate.Decision.Block)
            .code shouldBe MysqlSequenceCanonicityGate.DRIFT_ROW_CODE

        val trigger = decl(MysqlSequenceCanonicityStatus.DRIFT,
            MysqlSequenceCanonicityKind.SUPPORT_TRIGGER, driftField = "body_marker")
        (MysqlSequenceCanonicityGate.decide(trigger, MysqlSequenceCanonicityGate.OpIntent.DROP)
            as MysqlSequenceCanonicityGate.Decision.Block)
            .code shouldBe MysqlSequenceCanonicityGate.DRIFT_TRIGGER_CODE
    }

    // ── MISSING × Intent → context-dependent routing ───────────

    test("MISSING + CREATE intent proceeds for every kind (bootstrap will create)") {
        for (kind in MysqlSequenceCanonicityKind.entries) {
            MysqlSequenceCanonicityGate.decide(decl(MysqlSequenceCanonicityStatus.MISSING, kind),
                MysqlSequenceCanonicityGate.OpIntent.CREATE) shouldBe
                MysqlSequenceCanonicityGate.Decision.Proceed
        }
    }

    test("MISSING + DROP intent proceeds for every kind (idempotent / cascade)") {
        for (kind in MysqlSequenceCanonicityKind.entries) {
            MysqlSequenceCanonicityGate.decide(decl(MysqlSequenceCanonicityStatus.MISSING, kind),
                MysqlSequenceCanonicityGate.OpIntent.DROP) shouldBe
                MysqlSequenceCanonicityGate.Decision.Proceed
        }
    }

    test("MISSING + ALTER intent blocks with MISSING_FOR_ALTER code") {
        // ALTER cannot proceed against a missing row / catalog —
        // the operator must either switch op intent or restore the
        // sequence before re-running.
        for (kind in MysqlSequenceCanonicityKind.entries) {
            val decision = MysqlSequenceCanonicityGate.decide(decl(MysqlSequenceCanonicityStatus.MISSING, kind),
                MysqlSequenceCanonicityGate.OpIntent.ALTER)
                .shouldBeInstanceOf<MysqlSequenceCanonicityGate.Decision.Block>()
            decision.code shouldBe MysqlSequenceCanonicityGate.MISSING_FOR_ALTER_CODE
            decision.reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        }
    }

    // ── PROBE_RUNTIME_ERROR → Block regardless of intent ───────

    test("PROBE_RUNTIME_ERROR routes to PROBE_FAILED code with the underlying problem text") {
        val decision = MysqlSequenceCanonicityGate.decide(
            decl(MysqlSequenceCanonicityStatus.PROBE_RUNTIME_ERROR,
                MysqlSequenceCanonicityKind.SUPPORT_TABLE,
                problem = "Access denied for user 'foo'@'%'"),
            MysqlSequenceCanonicityGate.OpIntent.CREATE,
        ).shouldBeInstanceOf<MysqlSequenceCanonicityGate.Decision.Block>()
        decision.code shouldBe MysqlSequenceCanonicityGate.PROBE_RUNTIME_ERROR_CODE
        decision.reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        decision.message shouldContain "Access denied"
    }

    // ── NOT_RUN_* → Info, never Block ──────────────────────────

    test("NOT_RUN_FILE_TARGET emits an Info decision (no Migration-Blocker)") {
        val decision = MysqlSequenceCanonicityGate.decide(
            decl(MysqlSequenceCanonicityStatus.NOT_RUN_FILE_TARGET,
                MysqlSequenceCanonicityKind.SEQUENCE_ROW),
            MysqlSequenceCanonicityGate.OpIntent.CREATE,
        ).shouldBeInstanceOf<MysqlSequenceCanonicityGate.Decision.Info>()
        decision.code shouldBe MysqlSequenceCanonicityGate.NOT_RUN_FILE_TARGET_CODE
    }

    test("NOT_RUN_POLICY emits an Info decision (operator opted out)") {
        val decision = MysqlSequenceCanonicityGate.decide(
            decl(MysqlSequenceCanonicityStatus.NOT_RUN_POLICY,
                MysqlSequenceCanonicityKind.NEXTVAL_ROUTINE),
            MysqlSequenceCanonicityGate.OpIntent.ALTER,
        ).shouldBeInstanceOf<MysqlSequenceCanonicityGate.Decision.Info>()
        decision.code shouldBe MysqlSequenceCanonicityGate.NOT_RUN_POLICY_CODE
    }
})
