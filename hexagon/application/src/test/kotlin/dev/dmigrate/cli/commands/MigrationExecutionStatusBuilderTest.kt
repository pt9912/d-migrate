package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.driver.migration.DialectExecutionHints
import dev.dmigrate.driver.migration.ExecutionRecoverability
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.TransactionBehavior
import dev.dmigrate.driver.migration.TransactionBoundary
import dev.dmigrate.driver.migration.TransactionScope
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MigrationExecutionStatusBuilderTest : FunSpec({

    fun stmt(
        sql: String,
        opId: String,
        scope: TransactionScope,
        behavior: TransactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL,
    ) = MigrationDdlStatement(
        sql = sql,
        operationIds = setOf(opId),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
        transactionScope = scope,
        hints = DialectExecutionHints(transactionBehavior = behavior),
    )

    test("§G.3 groups contiguous statements and suffixes repeated operation groups") {
        val groups = MigrationExecutionStatusBuilder.statementGroups(
            listOf(
                stmt("ALTER TABLE a ADD COLUMN c INT;", "op-1", TransactionScope.RUNNER_OWNED),
                stmt("ALTER TABLE a ADD COLUMN d INT;", "op-1", TransactionScope.RUNNER_OWNED),
                stmt("ALTER TABLE b ADD COLUMN c INT;", "op-2", TransactionScope.RUNNER_OWNED),
                stmt("ALTER TABLE a ADD COLUMN e INT;", "op-1", TransactionScope.RUNNER_OWNED),
            ),
        )

        groups.map { it.statementGroupId } shouldBe listOf("op-1#1", "op-2", "op-1#2")
        groups.map { it.statementStartInclusive to it.statementEndExclusive } shouldBe
            listOf(0 to 2, 2 to 3, 3 to 4)
        groups.map { it.transactionBoundary }.distinct() shouldBe listOf(TransactionBoundary.INSIDE)
    }

    test("§G.3 stream-owned SQLite-like groups expose before inside after boundaries") {
        val outside = TransactionBehavior.NOT_TRANSACTIONAL
        val groups = MigrationExecutionStatusBuilder.statementGroups(
            listOf(
                stmt("PRAGMA foreign_keys = OFF;", "op-1", TransactionScope.STREAM_OWNED, outside),
                stmt("BEGIN IMMEDIATE;", "op-1", TransactionScope.STREAM_OWNED),
                stmt("CREATE TABLE x (id INT);", "op-1", TransactionScope.STREAM_OWNED),
                stmt("COMMIT;", "op-1", TransactionScope.STREAM_OWNED),
                stmt("PRAGMA foreign_keys = ON;", "op-1", TransactionScope.STREAM_OWNED, outside),
            ),
        )

        groups.map { it.transactionBoundary } shouldBe listOf(
            TransactionBoundary.BEFORE,
            TransactionBoundary.INSIDE,
            TransactionBoundary.AFTER,
        )
        groups.map { it.statementStartInclusive to it.statementEndExclusive } shouldBe
            listOf(0 to 1, 1 to 4, 4 to 5)
    }

    test("§G.3 recoverability derives from executor observations") {
        MigrationExecutionStatusBuilder.recoverability(
            ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                transactionRolledBack = true,
                sideEffectsPossible = false,
                executionError = "boom",
            ),
        ) shouldBe ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED

        MigrationExecutionStatusBuilder.recoverability(
            ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                transactionRolledBack = false,
                sideEffectsPossible = true,
                executionError = "boom",
            ),
        ) shouldBe ExecutionRecoverability.PARTIAL_STATE_POSSIBLE
    }
})
