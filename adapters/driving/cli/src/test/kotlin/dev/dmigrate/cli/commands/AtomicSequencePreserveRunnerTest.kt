package dev.dmigrate.cli.commands

import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.connection.ConnectionPool
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveExecutor
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.sql.Connection

/**
 * Atomic-Preserve Phase C.4 unit tests for
 * [AtomicSequencePreserveRunner]. The runner has two seams:
 *
 * 1. `dispatcher` — pure function from [DatabaseDialect] to
 *    [AtomicSequencePreserveExecutor]. Tested directly in
 *    [AtomicSequencePreserveDispatcherTest]; here we pass a fake
 *    dispatcher to verify routing and argument propagation.
 * 2. `acquireConnection` — production glue wraps Hikari + the named
 *    connection resolver. Tests pass a fake acquisition that yields
 *    a mocked [ConnectionPool] so the runner can be exercised
 *    without a live database. The production glue is exercised
 *    indirectly when [SchemaMigrateWiring] wires the runner in C.3.
 */
class AtomicSequencePreserveRunnerTest : FunSpec({

    val target = CompareOperand.Database("db:test-target")
    val emptyBatch = AtomicSequencePreserveBatch(
        requests = emptyList(),
        protectedOperationIds = emptyList(),
        internalFollowUpIds = emptyList(),
    )
    val noopExecuteProtectedOps: (Connection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult =
        { _, _ -> AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 0) }

    fun fakeAcquireFor(dialect: DatabaseDialect, conn: Connection): AtomicSequencePreserveRunner.AcquiredPool {
        val pool = mockk<ConnectionPool>(relaxed = true)
        every { pool.dialect } returns dialect
        every { pool.borrow() } returns conn
        return AtomicSequencePreserveRunner.AcquiredPool(dialect = dialect, pool = pool)
    }

    test("DEFAULT_LOCK_TIMEOUT_MILLIS matches §4.0 (5000 ms)") {
        AtomicSequencePreserveRunner.DEFAULT_LOCK_TIMEOUT_MILLIS shouldBe 5000L
    }

    test("lockTimeoutMillis = 0 throws IllegalArgumentException") {
        val ex = shouldThrow<IllegalArgumentException> {
            AtomicSequencePreserveRunner.execute(
                target = target,
                configPath = null,
                batch = emptyBatch,
                executeProtectedOperations = noopExecuteProtectedOps,
                lockTimeoutMillis = 0L,
                dispatcher = { error("dispatcher must not be called when arg validation fails") },
                acquireConnection = { _, _ -> error("acquireConnection must not be called when arg validation fails") },
            )
        }
        ex.message!!.contains("lockTimeoutMillis must be > 0") shouldBe true
    }

    test("negative lockTimeoutMillis throws IllegalArgumentException") {
        shouldThrow<IllegalArgumentException> {
            AtomicSequencePreserveRunner.execute(
                target = target,
                configPath = null,
                batch = emptyBatch,
                executeProtectedOperations = noopExecuteProtectedOps,
                lockTimeoutMillis = -1L,
                dispatcher = { error("must not reach") },
                acquireConnection = { _, _ -> error("must not reach") },
            )
        }
    }

    test("happy path: dispatcher resolves by acquired dialect, executor receives the args, pool is closed") {
        val conn = mockk<Connection>(relaxed = true)
        val acquired = fakeAcquireFor(DatabaseDialect.POSTGRESQL, conn)
        val fakeExecutor = mockk<AtomicSequencePreserveExecutor>(relaxed = true)
        val expectedResult = AtomicSequencePreserveResult.Applied(emptyList())
        val capturedTimeout = slot<Long>()
        every {
            fakeExecutor.execute(any(), any(), capture(capturedTimeout), any())
        } returns expectedResult

        val dispatcherCalls = mutableListOf<DatabaseDialect>()
        val result = AtomicSequencePreserveRunner.execute(
            target = target,
            configPath = null,
            batch = emptyBatch,
            executeProtectedOperations = noopExecuteProtectedOps,
            lockTimeoutMillis = 1234L,
            dispatcher = { d ->
                dispatcherCalls.add(d)
                fakeExecutor
            },
            acquireConnection = { _, _ -> acquired },
        )

        result shouldBe expectedResult
        dispatcherCalls shouldBe listOf(DatabaseDialect.POSTGRESQL)
        capturedTimeout.captured shouldBe 1234L
        verify(exactly = 1) { acquired.pool.borrow() }
        verify(exactly = 1) { acquired.pool.close() }
        verify(exactly = 1) { conn.close() }
    }

    test("happy path: default lockTimeoutMillis flows to the executor when caller omits it") {
        val conn = mockk<Connection>(relaxed = true)
        val acquired = fakeAcquireFor(DatabaseDialect.MYSQL, conn)
        val fakeExecutor = mockk<AtomicSequencePreserveExecutor>(relaxed = true)
        val capturedTimeout = slot<Long>()
        every {
            fakeExecutor.execute(any(), any(), capture(capturedTimeout), any())
        } returns AtomicSequencePreserveResult.Applied(emptyList())

        AtomicSequencePreserveRunner.execute(
            target = target,
            configPath = null,
            batch = emptyBatch,
            executeProtectedOperations = noopExecuteProtectedOps,
            dispatcher = { fakeExecutor },
            acquireConnection = { _, _ -> acquired },
        )

        capturedTimeout.captured shouldBe AtomicSequencePreserveRunner.DEFAULT_LOCK_TIMEOUT_MILLIS
    }

    test("happy path: executor result is returned unchanged for each Result subtype") {
        val cases = listOf(
            AtomicSequencePreserveResult.Applied(emptyList()),
            AtomicSequencePreserveResult.NotFound(emptyList()),
            AtomicSequencePreserveResult.LockTimeout(emptyList()),
            AtomicSequencePreserveResult.Failed(
                ref = dev.dmigrate.core.diff.migration.SequenceObjectRef(
                    name = "foo",
                    dialect = dev.dmigrate.core.diff.migration.RenameProjectionDialect.POSTGRESQL,
                ),
                cause = IllegalStateException("boom"),
            ),
        )
        cases.forEach { expected ->
            val conn = mockk<Connection>(relaxed = true)
            val acquired = fakeAcquireFor(DatabaseDialect.SQLITE, conn)
            val fakeExecutor = mockk<AtomicSequencePreserveExecutor>(relaxed = true)
            every { fakeExecutor.execute(any(), any(), any(), any()) } returns expected

            val result = AtomicSequencePreserveRunner.execute(
                target = target,
                configPath = null,
                batch = emptyBatch,
                executeProtectedOperations = noopExecuteProtectedOps,
                dispatcher = { fakeExecutor },
                acquireConnection = { _, _ -> acquired },
            )
            result shouldBe expected
        }
    }

    test("pool is closed even when the executor throws") {
        val conn = mockk<Connection>(relaxed = true)
        val acquired = fakeAcquireFor(DatabaseDialect.POSTGRESQL, conn)
        val fakeExecutor = mockk<AtomicSequencePreserveExecutor>(relaxed = true)
        every {
            fakeExecutor.execute(any(), any(), any(), any())
        } throws RuntimeException("executor crashed")

        shouldThrow<RuntimeException> {
            AtomicSequencePreserveRunner.execute(
                target = target,
                configPath = null,
                batch = emptyBatch,
                executeProtectedOperations = noopExecuteProtectedOps,
                dispatcher = { fakeExecutor },
                acquireConnection = { _, _ -> acquired },
            )
        }
        verify(exactly = 1) { acquired.pool.close() }
        verify(exactly = 1) { conn.close() }
    }

    // ── defaultAcquireConnection production glue ───────────────────────

    test("default acquireConnection surfaces NamedConnectionResolver failure as CompareConfigException") {
        // Unknown alias with no config file: NamedConnectionResolver
        // looks up an alias that doesn't exist → throws.
        // AtomicSequencePreserveRunner catches the resolution failure
        // and rethrows as CompareConfigException (CLI exit 7), matching
        // SequenceCurrentValueProbeRunner / MysqlSequenceCanonicityProbeRunner.
        shouldThrow<CompareConfigException> {
            AtomicSequencePreserveRunner.execute(
                target = CompareOperand.Database("unknown-alias-that-does-not-exist"),
                configPath = null,
                batch = AtomicSequencePreserveBatch(
                    requests = listOf(
                        dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest(
                            sequenceRef = dev.dmigrate.core.diff.migration.SequenceObjectRef(
                                name = "seq",
                                dialect = dev.dmigrate.core.diff.migration.RenameProjectionDialect.POSTGRESQL,
                            ),
                            renderRestore = { _ -> emptyList() },
                        ),
                    ),
                    protectedOperationIds = emptyList(),
                    internalFollowUpIds = emptyList(),
                ),
                executeProtectedOperations = noopExecuteProtectedOps,
                // No dispatcher / acquireConnection overrides — exercise
                // the production default.
            )
        }
    }

    test("default acquireConnection surfaces ConnectionUrlParser failure as CompareConfigException") {
        // A `db:` operand whose source resolves to a malformed JDBC URL
        // routes through the parse-catch branch. The simplest reproducer
        // uses a raw URL with an unknown JDBC scheme.
        shouldThrow<CompareConfigException> {
            AtomicSequencePreserveRunner.execute(
                target = CompareOperand.Database("jdbc:no-such-driver-scheme://nowhere/db"),
                configPath = null,
                batch = AtomicSequencePreserveBatch(
                    requests = listOf(
                        dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest(
                            sequenceRef = dev.dmigrate.core.diff.migration.SequenceObjectRef(
                                name = "seq",
                                dialect = dev.dmigrate.core.diff.migration.RenameProjectionDialect.POSTGRESQL,
                            ),
                            renderRestore = { _ -> emptyList() },
                        ),
                    ),
                    protectedOperationIds = emptyList(),
                    internalFollowUpIds = emptyList(),
                ),
                executeProtectedOperations = noopExecuteProtectedOps,
            )
        }
    }
})
