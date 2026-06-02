package dev.dmigrate.driver

import dev.dmigrate.driver.migration.MigrationBlockedReason
import dev.dmigrate.driver.migration.PlannerBlockerClassifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class CheckPreflightGateTest : FunSpec({

    fun decl(
        status: CheckPreflightStatus,
        operationId: String = "op-1",
        table: String = "users",
        constraintName: String = "chk_age",
        expression: String = "age >= 0",
        totalRows: Long? = null,
        failingRows: Long? = null,
        sampleRowIds: List<String> = emptyList(),
        problem: String? = null,
    ) = CheckPreflightDeclaration(
        operationId = operationId,
        dialect = "postgresql",
        table = table,
        constraintName = constraintName,
        expression = expression,
        status = status,
        sqlHash = "h",
        totalRows = totalRows,
        failingRows = failingRows,
        sampleRowIds = sampleRowIds,
        problem = problem,
    )

    test("no declaration matching the operation id → Proceed") {
        CheckPreflightGate.decide("missing", listOf(decl(CheckPreflightStatus.FAILED))) shouldBe
            CheckPreflightGate.Decision.Proceed
    }

    test("empty declarations list → Proceed") {
        CheckPreflightGate.decide("op-1", emptyList()) shouldBe CheckPreflightGate.Decision.Proceed
    }

    test("PASSED → Proceed") {
        CheckPreflightGate.decide("op-1", listOf(decl(CheckPreflightStatus.PASSED))) shouldBe
            CheckPreflightGate.Decision.Proceed
    }

    test("NOT_RUN_FILE_TARGET → Proceed") {
        CheckPreflightGate.decide("op-1", listOf(decl(CheckPreflightStatus.NOT_RUN_FILE_TARGET))) shouldBe
            CheckPreflightGate.Decision.Proceed
    }

    test("NOT_RUN_POLICY → Proceed") {
        CheckPreflightGate.decide("op-1", listOf(decl(CheckPreflightStatus.NOT_RUN_POLICY))) shouldBe
            CheckPreflightGate.Decision.Proceed
    }

    test("FAILED → Block with CHECK_PREFLIGHT_VIOLATIONS + MANUAL_ACTION_REQUIRED") {
        val d = decl(CheckPreflightStatus.FAILED, failingRows = 5, totalRows = 100, sampleRowIds = listOf("1", "2"))
        val decision = CheckPreflightGate.decide("op-1", listOf(d)) as CheckPreflightGate.Decision.Block
        decision.code shouldBe PlannerBlockerClassifier.CHECK_PREFLIGHT_VIOLATIONS_CODE
        decision.reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        decision.message shouldContain "chk_age"
        decision.message shouldContain "age >= 0"
        decision.message shouldContain "Failing rows: 5"
        decision.message shouldContain "Total rows: 100"
        decision.message shouldContain "Sample row ids: 1, 2"
    }

    test("FAILED with no counts / no samples → message omits the optional fragments") {
        val d = decl(CheckPreflightStatus.FAILED)
        val decision = CheckPreflightGate.decide("op-1", listOf(d)) as CheckPreflightGate.Decision.Block
        decision.message shouldNotContain "Failing rows:"
        decision.message shouldNotContain "Total rows:"
        decision.message shouldNotContain "Sample row ids:"
    }

    test("PROBE_RUNTIME_ERROR → Block with CHECK_PREFLIGHT_RUNTIME_ERROR + MANUAL_ACTION_REQUIRED") {
        val d = decl(CheckPreflightStatus.PROBE_RUNTIME_ERROR, problem = "connection reset")
        val decision = CheckPreflightGate.decide("op-1", listOf(d)) as CheckPreflightGate.Decision.Block
        decision.code shouldBe PlannerBlockerClassifier.CHECK_PREFLIGHT_RUNTIME_ERROR_CODE
        decision.reason shouldBe MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        decision.message shouldContain "chk_age"
        decision.message shouldContain "connection reset"
    }

    test("PROBE_RUNTIME_ERROR with blank problem → message has no trailing problem text but still names the constraint") {
        val d = decl(CheckPreflightStatus.PROBE_RUNTIME_ERROR, problem = null)
        val decision = CheckPreflightGate.decide("op-1", listOf(d)) as CheckPreflightGate.Decision.Block
        decision.message shouldContain "chk_age"
    }

    test("first matching declaration wins when multiple entries share the operation id") {
        // Real callers should never emit duplicate declarations, but
        // the helper is forgiving — first-match wins, so the contract
        // is at least deterministic.
        val a = decl(CheckPreflightStatus.FAILED, failingRows = 1)
        val b = decl(CheckPreflightStatus.PROBE_RUNTIME_ERROR, problem = "x")
        val decision = CheckPreflightGate.decide("op-1", listOf(a, b)) as CheckPreflightGate.Decision.Block
        decision.code shouldBe PlannerBlockerClassifier.CHECK_PREFLIGHT_VIOLATIONS_CODE
    }
})
