package dev.dmigrate.driver.migration

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Das Ausfuehrungsmodell eines Abschnitts. Die Entscheidung hat zwei
 * Ausfuehrer — den produktiven und die Test-Fixture —, und sie darf nur an
 * einer Stelle stehen: als sie zweimal geschrieben war, kannte eine der
 * Kopien `NO_TRANSACTION` nicht.
 */
class MigrationStreamClassifierScopeTest : FunSpec({

    fun stmt(scope: TransactionScope, behavior: TransactionBehavior = TransactionBehavior.FULLY_TRANSACTIONAL) =
        MigrationDdlStatement(
            sql = "SELECT 1",
            operationIds = setOf("op"),
            risk = OperationRisk.SAFE,
            phase = DiffPhase.TABLES,
            transactionScope = scope,
            hints = DialectExecutionHints(transactionBehavior = behavior),
        )

    test("ein Abschnitt ohne Transaktion wird als solcher erkannt") {
        MigrationStreamClassifier.executionModel(listOf(stmt(TransactionScope.NO_TRANSACTION))) shouldBe
            StreamExecutionModel.NO_TRANSACTION
    }

    test("ein stream-eigener Abschnitt behaelt seine Grenzen") {
        MigrationStreamClassifier.executionModel(listOf(stmt(TransactionScope.STREAM_OWNED))) shouldBe
            StreamExecutionModel.STREAM_TRANSACTION
    }

    test("alles andere laeuft in der Transaktion des Laufs — auch der leere Abschnitt") {
        MigrationStreamClassifier.executionModel(listOf(stmt(TransactionScope.RUNNER_OWNED))) shouldBe
            StreamExecutionModel.RUNNER_TRANSACTION
        MigrationStreamClassifier.executionModel(emptyList()) shouldBe StreamExecutionModel.RUNNER_TRANSACTION
    }

    test("NO_TRANSACTION neben der Transaktion des Laufs ist ausfuehrbar, mit STREAM_OWNED nicht") {
        val mixed = listOf(stmt(TransactionScope.RUNNER_OWNED), stmt(TransactionScope.NO_TRANSACTION))
        MigrationStreamClassifier.unsupportedTransactionScopeReason(mixed) shouldBe null

        val withStream = listOf(stmt(TransactionScope.STREAM_OWNED), stmt(TransactionScope.NO_TRANSACTION))
        (MigrationStreamClassifier.unsupportedTransactionScopeReason(withStream) != null) shouldBe true
    }
})
