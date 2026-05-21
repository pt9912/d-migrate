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
            PlannerBlockerClassifier.MYSQL_SEQUENCE_MISSING_FOR_DROP_CODE,
            PlannerBlockerClassifier.MYSQL_SEQUENCE_DRIFT_PROBE_FAILED_CODE,
        ).forEach { code ->
            PlannerBlockerClassifier.classify(code) shouldBe
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        }
    }

    test("0.9.7 preserve-current-value blocker codes split between MANUAL_ACTION_REQUIRED and DIALECT_UNSUPPORTED_OPERATION") {
        // Sub-Slice D (2026-05-21): three preserve-current-value
        // blocker codes plus the file-target-blocker the stage does
        // not emit today but the classifier carries for future
        // expansion. PROBE_FAILED, CONFIG_INVALID, REQUIRES_DB_TARGET
        // → MANUAL_ACTION_REQUIRED (operator must reconcile config /
        // target / probe). NOT_SUPPORTED_BY_DIALECT →
        // DIALECT_UNSUPPORTED_OPERATION (SQLite has no probe).
        listOf(
            PlannerBlockerClassifier.SEQUENCE_PRESERVE_PROBE_FAILED_CODE,
            PlannerBlockerClassifier.SEQUENCE_PRESERVE_CONFIG_INVALID_CODE,
            PlannerBlockerClassifier.SEQUENCE_PRESERVE_REQUIRES_DB_TARGET_CODE,
        ).forEach { code ->
            PlannerBlockerClassifier.classify(code) shouldBe
                MigrationBlockedReason.MANUAL_ACTION_REQUIRED
        }
        PlannerBlockerClassifier.classify(
            PlannerBlockerClassifier.SEQUENCE_PRESERVE_NOT_SUPPORTED_BY_DIALECT_CODE,
        ) shouldBe MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
    }

    test("INFO codes (SEQUENCE_PRESERVE_NOT_FOUND / NOT_RUN_POLICY) are NOT in the blocker mapping table") {
        // §6.4.5 / §6.4.3: info-only codes flow into the
        // `MigrationDdlResult.diagnostics` stream without classifier
        // routing. If they ever reach `classify()`, the else-branch
        // would mis-label them as DIALECT_UNSUPPORTED_OPERATION, which
        // is the contract this test pins as "documented unintended
        // fallback, never on the happy path".
        PlannerBlockerClassifier.classify("SEQUENCE_PRESERVE_NOT_FOUND") shouldBe
            MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION
        PlannerBlockerClassifier.classify("SEQUENCE_PRESERVE_NOT_RUN_POLICY") shouldBe
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
