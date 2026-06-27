package dev.dmigrate.driver.migration.preserve

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.connection.DatabaseConnection
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk

/**
 * Atomic-Preserve Phase B (2026-05-31): Phase-B-side contract pin
 * for the new port + DTOs in `hexagon:ports-execute`. The executor
 * implementations land in B.2/B.3/B.4 (per-dialect adapters); this
 * file only asserts the shape of the value carriers + sealed result
 * hierarchy + ProtectedOperationId invariants, so a future renaming
 * or sealed-subtype-flattening surfaces here, not in a downstream
 * adapter test.
 */
class AtomicSequencePreserveContractTest : FunSpec({

    val pgSeq = SequenceObjectRef(name = "users_id_seq", dialect = RenameProjectionDialect.POSTGRESQL)
    val mysqlSeq = SequenceObjectRef(name = "events_id_seq", dialect = RenameProjectionDialect.MYSQL)

    test("ProtectedOperationId rejects blank values") {
        val blanks = listOf("", " ", "\t", "\n", "  \n\t")
        blanks.forEach { value ->
            runCatching { ProtectedOperationId(value) }
                .onSuccess { error("blank value '$value' (escaped) must throw IllegalArgumentException") }
                .onFailure { it.shouldBeInstanceOf<IllegalArgumentException>() }
        }
        // Non-blank values pass.
        ProtectedOperationId("AlterSequenceCurrentValue").value shouldBe "AlterSequenceCurrentValue"
    }

    test("AtomicSequencePreserveResult.Applied carries the refs in commit order") {
        val refs = listOf(pgSeq, mysqlSeq)
        val applied = AtomicSequencePreserveResult.Applied(refs)
        applied.refs shouldBe refs
    }

    test("AtomicSequencePreserveResult.LockTimeout carries the partial ref subset") {
        // The plan-doc contract: LockTimeout may carry a partial
        // subset (one sequence in a multi-sequence batch may time
        // out while siblings already finished probing). The result
        // exposes exactly that subset; the transaction itself is
        // rolled back, so the runner sees "these sequences could
        // not be locked" without inferring whole-batch failure.
        val partial = listOf(mysqlSeq)
        val timeout = AtomicSequencePreserveResult.LockTimeout(partial)
        timeout.refs shouldBe partial
        timeout.refs.size shouldBe 1
    }

    test("AtomicSequencePreserveResult.Failed pins the offending sequence + cause") {
        val cause = IllegalStateException("simulated restore SQL failure")
        val failed = AtomicSequencePreserveResult.Failed(pgSeq, cause)
        failed.ref shouldBe pgSeq
        failed.cause shouldBe cause
    }

    test("AtomicSequencePreserveBatch sort + de-dupe is the executor's job, not the batch's") {
        // The batch is a passive carrier — it does NOT enforce
        // sorted order on construction (the deterministic lock
        // order from plan-doc §2 (3) is the executor's
        // responsibility, not the value carrier's). This test pins
        // that the batch accepts the requests in caller order so
        // the executor's reordering is observable.
        val unsorted = listOf(
            AtomicSequencePreserveRequest(mysqlSeq, { listOf("/* mysql restore */") }),
            AtomicSequencePreserveRequest(pgSeq, { listOf("/* pg restore */") }),
        )
        val batch = AtomicSequencePreserveBatch(
            requests = unsorted,
            protectedOperationIds = listOf(ProtectedOperationId("AlterSequenceCurrentValue")),
            internalFollowUpIds = listOf("op-42", "op-43"),
        )
        batch.requests shouldBe unsorted
        batch.requests.map { it.sequenceRef } shouldBe listOf(mysqlSeq, pgSeq)
    }

    test("AtomicProtectedExecutionResult.Succeeded carries the statements-executed count") {
        val succeeded = AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 7)
        succeeded.statementsExecuted shouldBe 7
    }

    test("requireOwnedConnection accepts an autocommit=true connection") {
        val owned = mockk<DatabaseConnection>(relaxed = true)
        every { owned.autoCommit } returns true
        // Should not throw.
        AtomicSequencePreserveExecutor.requireOwnedConnection(owned)
    }

    test("requireOwnedConnection throws IllegalStateException for an enclosing transaction") {
        val enclosed = mockk<DatabaseConnection>(relaxed = true)
        every { enclosed.autoCommit } returns false
        val ex = shouldThrow<IllegalStateException> {
            AtomicSequencePreserveExecutor.requireOwnedConnection(enclosed)
        }
        ex.message!!.contains("requires an owned, non-enclosed connection") shouldBe true
        ex.message!!.contains("autoCommit=true at entry") shouldBe true
    }
})
