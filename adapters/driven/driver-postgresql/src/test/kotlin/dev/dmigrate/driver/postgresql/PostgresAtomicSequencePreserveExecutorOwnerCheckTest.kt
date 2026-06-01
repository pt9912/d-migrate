package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection

/**
 * Atomic-Preserve Phase C.3 owner-vertrag pin for the PG executor.
 * Verifies the executor delegates to
 * `AtomicSequencePreserveExecutor.requireOwnedConnection` once it
 * starts touching the connection — a non-empty batch with
 * `autoCommit=false` (enclosing transaction) must surface
 * [IllegalStateException] **before** any lock or probe runs. The
 * contract test in `:hexagon:ports-execute` covers the helper
 * itself; this test pins the adapter call-site. Empty batches
 * short-circuit before the check (see
 * [PostgresAtomicSequencePreserveExecutorIntegrationTest] for the
 * no-connection-touch contract).
 */
class PostgresAtomicSequencePreserveExecutorOwnerCheckTest : FunSpec({

    test("execute throws IllegalStateException when connection is in an enclosing transaction") {
        val enclosed = mockk<Connection>(relaxed = true)
        every { enclosed.autoCommit } returns false
        val executor = PostgresAtomicSequencePreserveExecutor()
        val ref = SequenceObjectRef(name = "seq_owner_check", dialect = RenameProjectionDialect.POSTGRESQL)
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(AtomicSequencePreserveRequest(ref) { _ -> emptyList() }),
            protectedOperationIds = emptyList(),
            internalFollowUpIds = emptyList(),
        )
        val ex = shouldThrow<IllegalStateException> {
            executor.execute(
                connection = enclosed,
                batch = batch,
                lockTimeoutMillis = 5_000L,
                executeProtectedOperations = { _, _ ->
                    AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0)
                },
            )
        }
        ex.message!!.contains("requires an owned, non-enclosed connection") shouldBe true
    }
})
