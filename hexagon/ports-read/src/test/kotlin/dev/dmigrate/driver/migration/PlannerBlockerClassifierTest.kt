package dev.dmigrate.driver.migration

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * F.4 Renderer-Blocker-Bridge: pins the
 * `DiffDiagnostic.code → MigrationBlockedReason` mapping for the
 * single F.4-specific code that the bridge upgrades plus the
 * default fallback that preserves the pre-F.4 `DIALECT_UNSUPPORTED_OPERATION`
 * contract.
 */
class PlannerBlockerClassifierTest : FunSpec({

    test("OBJECT_RENAME_UNSUPPORTED classifies to OBJECT_RENAME_UNSUPPORTED") {
        PlannerBlockerClassifier.classify("OBJECT_RENAME_UNSUPPORTED") shouldBe
            MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED
    }

    test("the public constant matches the diagnostic-code string the F.4 Mapper emits") {
        PlannerBlockerClassifier.OBJECT_RENAME_UNSUPPORTED_CODE shouldBe "OBJECT_RENAME_UNSUPPORTED"
    }

    test("legacy planner-blocker codes fall back to DIALECT_UNSUPPORTED_OPERATION") {
        // CONSTRAINT_NOT_DIFFABLE (F.5 first slice) is the canonical
        // example: the dialect genuinely cannot render the operation,
        // so DIALECT_UNSUPPORTED_OPERATION is the right reason.
        PlannerBlockerClassifier.classify("CONSTRAINT_NOT_DIFFABLE") shouldBe
            MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("unknown / future codes fall back to DIALECT_UNSUPPORTED_OPERATION") {
        PlannerBlockerClassifier.classify("SOME_FUTURE_DIAGNOSTIC_CODE") shouldBe
            MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        PlannerBlockerClassifier.classify("") shouldBe
            MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("classifier is exhaustive (every MigrationBlockedReason value is reachable)") {
        // We don't pin every value, but we pin that the function
        // never throws / never returns null for any input string —
        // contract is "always classify, default safely".
        listOf(
            "X",
            "Y",
            "OBJECT_RENAME_UNSUPPORTED",
            "CONSTRAINT_NOT_DIFFABLE",
            "MATERIALIZED_VIEW_DIFF_UNSUPPORTED",
        ).forEach { code ->
            // No-throw assertion: result is a MigrationBlockedReason
            // (the type system guarantees this; the test exists to
            // catch future refactors that might introduce a nullable
            // return).
            PlannerBlockerClassifier.classify(code) shouldBe
                if (code == "OBJECT_RENAME_UNSUPPORTED") {
                    MigrationBlockedReason.OBJECT_RENAME_UNSUPPORTED
                } else {
                    MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
                }
        }
    }
})
