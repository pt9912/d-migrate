package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MigrationDdlResultTest : FunSpec({

    fun stmt(id: String, sql: String = "SELECT 1") = MigrationDdlStatement(
        sql = sql,
        operationIds = setOf(id),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
    )

    test("empty plan: isEmpty true, isBlocked false, no init violations") {
        val r = MigrationDdlResult(
            statements = emptyList(),
            operationsRendered = emptySet(),
            spatialProfile = "POSTGIS",
        )
        r.isEmpty shouldBe true
        r.isBlocked shouldBe false
        r.spatialProfile shouldBe "POSTGIS"
    }

    test("non-empty rendered + skipped operations are disjoint — overlap throws") {
        shouldThrow<IllegalArgumentException> {
            MigrationDdlResult(
                statements = listOf(stmt("op-a")),
                operationsRendered = setOf("op-a", "op-b"),
                operationsSkipped = setOf("op-b"),
            )
        }
    }

    test("primaryBlockedReason without any blockers throws") {
        shouldThrow<IllegalArgumentException> {
            MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                primaryBlockedReason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            )
        }
    }

    test("primaryBlockedReason missing from blockers list throws") {
        shouldThrow<IllegalArgumentException> {
            MigrationDdlResult(
                statements = emptyList(),
                operationsRendered = emptySet(),
                blockers = listOf(MigrationBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE)),
                primaryBlockedReason = MigrationBlockedReason.MANUAL_ACTION_REQUIRED,
            )
        }
    }

    test("primaryBlockedReason listed in blockers is accepted") {
        val r = MigrationDdlResult(
            statements = emptyList(),
            operationsRendered = emptySet(),
            blockers = listOf(MigrationBlocker(MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE)),
            primaryBlockedReason = MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE,
        )
        r.isBlocked shouldBe true
        r.primaryBlockedReason shouldBe MigrationBlockedReason.ROLLBACK_NOT_POSSIBLE
    }

    test("MigrationDdlStatement carries operationIds, risk, phase, notes") {
        val s = MigrationDdlStatement(
            sql = "ALTER TABLE x ADD COLUMN y TEXT",
            operationIds = setOf("op-1", "op-2"),
            risk = OperationRisk(destructive = false),
            phase = DiffPhase.COLUMNS,
        )
        s.operationIds.size shouldBe 2
        s.phase shouldBe DiffPhase.COLUMNS
    }

    test("MigrationBlocker default operationIds and diagnostics are empty") {
        val b = MigrationBlocker(reason = MigrationBlockedReason.DIALECT_UNSUPPORTED_OPERATION)
        b.operationIds.isEmpty() shouldBe true
        b.diagnostics.isEmpty() shouldBe true
    }

    test("G.3 execution statement groups and recoverability are carried by result") {
        val group = MigrationExecutionStatementGroup(
            statementGroupId = "op-a",
            operationIds = setOf("op-a"),
            statementStartInclusive = 0,
            statementEndExclusive = 1,
            transactionScope = TransactionScope.RUNNER_OWNED,
            transactionBoundary = TransactionBoundary.INSIDE,
        )
        val r = MigrationDdlResult(
            statements = listOf(stmt("op-a")),
            operationsRendered = setOf("op-a"),
            executionStatementGroups = listOf(group),
            recoverability = ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED,
        )

        r.executionStatementGroups.single() shouldBe group
        r.recoverability shouldBe ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED
    }

    test("G.3 transaction-scope blocker reason is a stable enum value") {
        MigrationBlockedReason.TRANSACTION_SCOPE_UNSUPPORTED.name shouldBe "TRANSACTION_SCOPE_UNSUPPORTED"
        TransactionBoundary.entries.map { it.name } shouldBe listOf("BEFORE", "INSIDE", "AFTER", "NONE")
        ExecutionRecoverability.entries.map { it.name } shouldBe listOf(
            "FULL_ROLLBACK_CONFIRMED",
            "ROLLBACK_ATTEMPTED",
            "PARTIAL_STATE_POSSIBLE",
            "UNKNOWN",
        )
    }

    test("MigrationBlockedReason ordinals stay stable across slices (F.4 + E.2)") {
        // F.4 rename-mapping-invalid-enum §4.1, E.2 sub-slice A.1 and
        // F.4 sub-slice A.1 all append new values at the end of
        // MigrationBlockedReason so existing ordinals (used by report
        // fixtures and tooling clients that serialise via `ordinal()`
        // or `entries.indexOf`) stay unchanged. The list is asserted
        // in full so accidentally inserting a new value in the middle
        // fails loudly.
        MigrationBlockedReason.entries.map { it.name } shouldBe listOf(
            "DESTRUCTIVE_OPERATION_REQUIRES_CONFIRMATION",
            "ROLLBACK_NOT_POSSIBLE",
            "MANUAL_ACTION_REQUIRED",
            "TARGET_STATE_MISMATCH",
            "TARGET_DIALECT_MISMATCH",
            "DIALECT_UNSUPPORTED_OPERATION",
            "TRANSACTION_SCOPE_UNSUPPORTED",
            "RENAME_MAPPING_INVALID",
            "TRIGGER_NAME_COLLISION",
            "TRIGGER_BODY_NOT_FUNCTION_REFERENCE",
            "OBJECT_RENAME_UNSUPPORTED",
        )
        // F.4 ordinal pin: RENAME_MAPPING_INVALID stays at index 7
        // even after E.2 / F.4-A.1 appended additional values at the end.
        MigrationBlockedReason.RENAME_MAPPING_INVALID.ordinal shouldBe 7
    }

    test("F.4 rename-mapping-invalid-enum: blocker carries new reason without breaking invariants") {
        // Smoke test that the new reason flows through MigrationDdlResult's
        // primaryBlockedReason invariant (must appear in blockers).
        val result = MigrationDdlResult(
            statements = emptyList(),
            operationsRendered = emptySet(),
            blockers = listOf(MigrationBlocker(MigrationBlockedReason.RENAME_MAPPING_INVALID)),
            primaryBlockedReason = MigrationBlockedReason.RENAME_MAPPING_INVALID,
        )
        result.isBlocked shouldBe true
        result.primaryBlockedReason shouldBe MigrationBlockedReason.RENAME_MAPPING_INVALID
    }

    test("G.1 dialect execution hints expose conservative defaults and explicit contracts") {
        DialectExecutionHints.UNKNOWN shouldBe DialectExecutionHints()
        TransactionScope.entries.map { it.name } shouldBe listOf(
            "RUNNER_OWNED",
            "STREAM_OWNED",
            "NO_TRANSACTION",
        )
        TransactionBehavior.entries.map { it.name } shouldBe listOf(
            "FULLY_TRANSACTIONAL",
            "IMPLICIT_COMMIT",
            "NOT_TRANSACTIONAL",
            "UNKNOWN",
        )
        LockBehavior.entries.map { it.name } shouldBe listOf(
            "NONE",
            "ROW",
            "METADATA",
            "TABLE_SHARED",
            "TABLE_EXCLUSIVE",
            "UNKNOWN",
        )

        val hints = DialectExecutionHints(
            transactionBehavior = TransactionBehavior.IMPLICIT_COMMIT,
            lockBehavior = LockBehavior.TABLE_EXCLUSIVE,
            implicitCommitPossible = true,
            sideEffectsPossible = true,
            requiresExclusiveAccess = true,
        )
        val statement = stmt("op-a").copy(
            transactionScope = TransactionScope.NO_TRANSACTION,
            hints = hints,
        )

        statement.transactionScope shouldBe TransactionScope.NO_TRANSACTION
        statement.hints.transactionBehavior shouldBe TransactionBehavior.IMPLICIT_COMMIT
        statement.hints.lockBehavior shouldBe LockBehavior.TABLE_EXCLUSIVE
        statement.hints.implicitCommitPossible shouldBe true
        statement.hints.sideEffectsPossible shouldBe true
        statement.hints.requiresExclusiveAccess shouldBe true
    }

    test("manualActions must be a subset of operationsRendered") {
        shouldThrow<IllegalArgumentException> {
            MigrationDdlResult(
                statements = listOf(stmt("op-a")),
                operationsRendered = setOf("op-a"),
                manualActions = setOf("op-b"),
            )
        }
    }

    test("destructiveOperations must be a subset of operationsRendered") {
        shouldThrow<IllegalArgumentException> {
            MigrationDdlResult(
                statements = listOf(stmt("op-a")),
                operationsRendered = setOf("op-a"),
                destructiveOperations = setOf("op-b"),
            )
        }
    }

    test("nonReversibleOperations must be a subset of operationsRendered") {
        shouldThrow<IllegalArgumentException> {
            MigrationDdlResult(
                statements = listOf(stmt("op-a")),
                operationsRendered = setOf("op-a"),
                nonReversibleOperations = setOf("op-b"),
            )
        }
    }

    test("every statement's operationIds must be a subset of operationsRendered") {
        shouldThrow<IllegalArgumentException> {
            MigrationDdlResult(
                statements = listOf(stmt("op-stale")),
                operationsRendered = setOf("op-fresh"),
            )
        }
    }
})
