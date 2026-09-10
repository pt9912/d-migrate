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

    test("ein implizites Commit steht in keiner Transaktion — auch wenn der Lauf eine fuehrt") {
        // MySQL und Oracle committen vor und nach jeder DDL-Anweisung. `INSIDE`
        // haette dort eine Ruecknahme behauptet, die es nicht gibt: scheitert
        // die zweite Anweisung, steht die erste.
        val groups = MigrationExecutionStatusBuilder.statementGroups(
            listOf(
                stmt("DROP VIEW v;", "op-1", TransactionScope.RUNNER_OWNED, TransactionBehavior.IMPLICIT_COMMIT),
                stmt(
                    "CREATE VIEW v AS SELECT 1;", "op-1",
                    TransactionScope.RUNNER_OWNED, TransactionBehavior.IMPLICIT_COMMIT,
                ),
            ),
        )

        groups.single().transactionBoundary shouldBe TransactionBoundary.NONE
        // Der Geltungsbereich bleibt, was er war — er sagt, WER die Transaktion
        // fuehrt, nicht ob die Anweisung darin geschuetzt ist.
        groups.single().transactionScope shouldBe TransactionScope.RUNNER_OWNED
    }

    test("ein nicht erklaertes Verhalten behauptet keine Ruecknahme") {
        // `TransactionBehavior.UNKNOWN` traegt die Regel selbst: der Bericht
        // darf dafuer keine vollstaendige Ruecknahme behaupten.
        val groups = MigrationExecutionStatusBuilder.statementGroups(
            listOf(
                stmt(
                    "ALTER TABLE a ADD COLUMN c INT;", "op-1",
                    TransactionScope.RUNNER_OWNED, TransactionBehavior.UNKNOWN,
                ),
            ),
        )

        groups.single().transactionBoundary shouldBe TransactionBoundary.NONE
    }

    test("nur ein erklaert transaktionales Statement steht INSIDE") {
        val groups = MigrationExecutionStatusBuilder.statementGroups(
            listOf(
                stmt("ALTER TABLE a ADD COLUMN c INT;", "op-1", TransactionScope.RUNNER_OWNED,
                    TransactionBehavior.FULLY_TRANSACTIONAL),
            ),
        )

        groups.single().transactionBoundary shouldBe TransactionBoundary.INSIDE
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

    test("ein Rueckbau neben bekannten Seiteneffekten ist kein vollstaendiger Rueckbau") {
        // Die Lage entsteht, sobald ein frueherer Abschnitt committet hat —
        // etwa eine Anweisung, die ausserhalb der Transaktion laufen musste.
        // Der gescheiterte Abschnitt rollt sauber zurueck, die Datenbank ist
        // trotzdem veraendert.
        MigrationExecutionStatusBuilder.recoverability(
            ExecutionTrace(
                executionStarted = true,
                executionCompleted = false,
                transactionRolledBack = true,
                sideEffectsPossible = true,
                executionError = "boom",
            ),
        ) shouldBe ExecutionRecoverability.PARTIAL_STATE_POSSIBLE
    }

    test("ohne Fehler gibt es keine Einstufung") {
        MigrationExecutionStatusBuilder.recoverability(
            ExecutionTrace(executionStarted = true, executionCompleted = true),
        ) shouldBe null
    }
})
