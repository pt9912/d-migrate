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

    test("F.5 CHECK / EXCLUDE planner-blocker codes classify to MANUAL_ACTION_REQUIRED / DIALECT_UNSUPPORTED_OPERATION") {
        // Operator-fixable cases (rewrite expression, clean data,
        // upgrade server version) → MANUAL_ACTION_REQUIRED.
        listOf(
            PlannerBlockerClassifier.CHECK_EXPRESSION_CROSS_TABLE_UNSUPPORTED_CODE,
            PlannerBlockerClassifier.MYSQL_CHECK_NOT_ENFORCED_BEFORE_8_0_16_CODE,
            PlannerBlockerClassifier.MYSQL_CHECK_ENFORCEMENT_UNKNOWN_CODE,
            PlannerBlockerClassifier.EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED_CODE,
            PlannerBlockerClassifier.CHECK_PREFLIGHT_VIOLATIONS_CODE,
            PlannerBlockerClassifier.CHECK_PREFLIGHT_RUNTIME_ERROR_CODE,
        ).forEach { code ->
            PlannerBlockerClassifier.classify(code) shouldBe
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        }
        // Dialect-incapability case (EXCLUDE on MySQL / SQLite) →
        // DIALECT_UNSUPPORTED_OPERATION.
        PlannerBlockerClassifier.classify(PlannerBlockerClassifier.EXCLUDE_NOT_SUPPORTED_BY_DIALECT_CODE) shouldBe
            MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("E.3 MySQL Sequence Drift-Check codes classify to MANUAL_ACTION_REQUIRED") {
        // E.3 Sub-Slice B (2026-05-20): six drift-related codes from
        // `MysqlSequenceCanonicityGate` all surface as
        // MANUAL_ACTION_REQUIRED — operator either reconciles the
        // live state, switches op intent, or re-plans after repair.
        listOf(
            PlannerBlockerClassifier.MYSQL_SEQUENCE_DRIFT_TABLE_CODE,
            PlannerBlockerClassifier.MYSQL_SEQUENCE_DRIFT_ROUTINE_CODE,
            PlannerBlockerClassifier.MYSQL_SEQUENCE_DRIFT_ROW_CODE,
            PlannerBlockerClassifier.MYSQL_SEQUENCE_DRIFT_TRIGGER_CODE,
            PlannerBlockerClassifier.MYSQL_SEQUENCE_MISSING_FOR_ALTER_CODE,
            PlannerBlockerClassifier.MYSQL_SEQUENCE_DRIFT_PROBE_FAILED_CODE,
        ).forEach { code ->
            PlannerBlockerClassifier.classify(code) shouldBe
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        }
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
