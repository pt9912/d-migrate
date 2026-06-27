package dev.dmigrate.driver.sqlite

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
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import java.sql.Connection

/**
 * Atomic-Preserve Phase C.3 owner-vertrag pin for the SQLite
 * executor. Mirror of the PG and MySQL pins; see
 * `AtomicSequencePreserveContractTest` for the shared helper's
 * unit coverage. Empty batches short-circuit before the check.
 */
class SqliteAtomicSequencePreserveExecutorOwnerCheckTest : FunSpec({

    test("execute throws IllegalStateException when connection is in an enclosing transaction") {
        val enclosed = mockk<Connection>(relaxed = true)
        every { enclosed.autoCommit } returns false
        val executor = SqliteAtomicSequencePreserveExecutor()
        val ref = SequenceObjectRef(name = "seq_owner_check", dialect = RenameProjectionDialect.SQLITE)
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(AtomicSequencePreserveRequest(ref) { _ -> emptyList() }),
            protectedOperationIds = emptyList(),
            internalFollowUpIds = emptyList(),
        )
        val ex = shouldThrow<IllegalStateException> {
            executor.execute(
                connection = JdbcDatabaseConnection(enclosed),
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
