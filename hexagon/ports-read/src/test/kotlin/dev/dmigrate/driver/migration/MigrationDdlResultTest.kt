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
        )
        r.isEmpty shouldBe true
        r.isBlocked shouldBe false
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
