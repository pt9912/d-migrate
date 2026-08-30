package dev.dmigrate.cli.commands

import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.connection.JdbcDatabaseConnection
import dev.dmigrate.driver.migration.ExecutionRecoverability
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.preserve.AtomicPreserveSegment
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import dev.dmigrate.driver.migration.preserve.ExecutableSegment
import dev.dmigrate.driver.migration.preserve.NoTransactionSegment
import dev.dmigrate.driver.migration.preserve.PlainSqlSegment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.sql.Connection
import java.sql.Statement

/**
 * Atomic-Preserve Phase C.3 unit tests for
 * [SegmentAwareMigrationExecutor]. The runner has two seams the
 * tests substitute with fakes:
 *
 * - `plainExecutor` — production points to
 *   [JdbcMigrationExecutor.execute]; tests pass a recording lambda.
 * - `atomicRunner` — production wraps
 *   [AtomicSequencePreserveRunner.execute]; tests pass a fake
 *   returning a specific [AtomicSequencePreserveResult] subtype.
 *
 * Together with the property-style coverage in
 * `ExecutableSegmentsTest`, this file pins how the four atomic
 * result subtypes map to [ExecutionTrace] and how multi-segment
 * plans short-circuit on the first failure.
 */
class SegmentAwareMigrationExecutorTest : FunSpec({

    val target = CompareOperand.Database("db:test-target")

    fun stmt(id: String, sql: String = "SELECT 1 -- $id") = MigrationDdlStatement(
        sql = sql,
        operationIds = setOf(id),
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
    )

    val pgSeq = SequenceObjectRef(name = "users_id_seq", dialect = RenameProjectionDialect.POSTGRESQL)
    val noopRestore: (dev.dmigrate.driver.SequenceCurrentValueProbeResult.Read) -> List<String> = { emptyList() }

    fun batch(
        protectedIds: List<String> = listOf("AlterSequence"),
        followUpIds: List<String> = emptyList(),
    ) = AtomicSequencePreserveBatch(
        requests = listOf(AtomicSequencePreserveRequest(pgSeq, noopRestore)),
        protectedOperationIds = protectedIds.map { ProtectedOperationId(it) },
        internalFollowUpIds = followUpIds,
    )

    test("empty segments → completed no-op trace") {
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = emptyList(),
            plainExecutor = { _, _, _ -> error("must not be called for empty segments") },
            atomicRunner = { _, _, _, _, _, _ -> error("must not be called for empty segments") },
        )
        trace.executionStarted shouldBe true
        trace.executionCompleted shouldBe true
        trace.statementsAttempted shouldBe 0
        trace.transactionRolledBack shouldBe false
    }

    test("single PlainSqlSegment is delegated to plainExecutor unchanged") {
        val statements = listOf(stmt("op1"), stmt("op2"))
        val expected = ExecutionTrace(
            executionStarted = true,
            executionCompleted = true,
            statementsAttempted = 2,
            lastStatementOperationIds = setOf("op2"),
        )
        val plainCalls = mutableListOf<List<MigrationDdlStatement>>()
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(PlainSqlSegment(statements)),
            plainExecutor = { _, s, _ ->
                plainCalls.add(s)
                expected
            },
            atomicRunner = { _, _, _, _, _, _ -> error("atomic runner must not be called for plain segments") },
        )
        plainCalls shouldBe listOf(statements)
        trace.executionCompleted shouldBe true
        trace.statementsAttempted shouldBe 2
        trace.lastStatementOperationIds shouldBe setOf("op2")
    }

    test("AtomicPreserveSegment Applied → trace executionCompleted, attempted = segment.statements.size") {
        val statements = listOf(stmt("AlterSequence"), stmt("AlterSequence-followup"))
        val segment = AtomicPreserveSegment(batch = batch(), statements = statements)
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(segment),
            plainExecutor = { _, _, _ -> error("must not be called") },
            atomicRunner = { _, _, _, _, _, _ -> AtomicSequencePreserveResult.Applied(listOf(pgSeq)) },
        )
        trace.executionCompleted shouldBe true
        trace.statementsAttempted shouldBe 2
        trace.lastStatementOperationIds shouldBe setOf("AlterSequence-followup")
        trace.transactionRolledBack shouldBe false
    }

    test("AtomicPreserveSegment NotFound → transactionRolledBack, error names the sequence, FULL_ROLLBACK_CONFIRMED") {
        val segment = AtomicPreserveSegment(batch = batch(), statements = listOf(stmt("AlterSequence")))
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(segment),
            plainExecutor = { _, _, _ -> error("must not be called") },
            atomicRunner = { _, _, _, _, _, _ -> AtomicSequencePreserveResult.NotFound(listOf(pgSeq)) },
        )
        trace.executionCompleted shouldBe false
        trace.transactionRolledBack shouldBe true
        trace.executionError!! shouldContain "users_id_seq"
        trace.executionError!! shouldContain "not found"
        trace.recoverability shouldBe ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED
    }

    test("AtomicPreserveSegment LockTimeout → SEQUENCE_PRESERVE_LOCK_TIMEOUT in executionError, FULL_ROLLBACK_CONFIRMED") {
        val segment = AtomicPreserveSegment(batch = batch(), statements = listOf(stmt("AlterSequence")))
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(segment),
            plainExecutor = { _, _, _ -> error("must not be called") },
            atomicRunner = { _, _, _, _, _, _ -> AtomicSequencePreserveResult.LockTimeout(listOf(pgSeq)) },
        )
        trace.executionCompleted shouldBe false
        trace.transactionRolledBack shouldBe true
        trace.executionError!! shouldContain "SEQUENCE_PRESERVE_LOCK_TIMEOUT"
        trace.executionError!! shouldContain "users_id_seq"
        trace.recoverability shouldBe ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED
    }

    test("AtomicPreserveSegment Cancelled → rolled back, error names reason + sequence, FULL_ROLLBACK_CONFIRMED") {
        val segment = AtomicPreserveSegment(batch = batch(), statements = listOf(stmt("AlterSequence")))
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(segment),
            plainExecutor = { _, _, _ -> error("must not be called") },
            atomicRunner = { _, _, _, _, _, _ ->
                AtomicSequencePreserveResult.Cancelled(refs = listOf(pgSeq), reason = "operator cancel")
            },
        )
        trace.executionStarted shouldBe true
        trace.executionCompleted shouldBe false
        trace.transactionRolledBack shouldBe true
        trace.statementsAttempted shouldBe 0
        trace.executionError!! shouldContain "cancelled"
        trace.executionError!! shouldContain "operator cancel"
        trace.executionError!! shouldContain "users_id_seq"
        trace.recoverability shouldBe ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED
    }

    test("AtomicPreserveSegment Failed → executionError carries the cause, FULL_ROLLBACK_CONFIRMED") {
        val segment = AtomicPreserveSegment(batch = batch(), statements = listOf(stmt("AlterSequence")))
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(segment),
            plainExecutor = { _, _, _ -> error("must not be called") },
            atomicRunner = { _, _, _, _, _, _ ->
                AtomicSequencePreserveResult.Failed(ref = pgSeq, cause = RuntimeException("boom"))
            },
        )
        trace.transactionRolledBack shouldBe true
        trace.executionError!! shouldContain "users_id_seq"
        trace.executionError!! shouldContain "boom"
        trace.recoverability shouldBe ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED
    }

    test("AtomicPreserveSegment Failed with null cause message → falls back to cause class name") {
        val segment = AtomicPreserveSegment(batch = batch(), statements = listOf(stmt("AlterSequence")))
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(segment),
            plainExecutor = { _, _, _ -> error("must not be called") },
            atomicRunner = { _, _, _, _, _, _ ->
                AtomicSequencePreserveResult.Failed(ref = pgSeq, cause = IllegalStateException())
            },
        )
        trace.executionError!! shouldContain "IllegalStateException"
    }

    test("internalFollowUpIds are filtered out of executeProtectedOperations") {
        // Compose a segment with one protected statement + one follow-
        // up statement; the runner-built executeProtectedOps callback
        // must only run the protected one (the atomic-executor handles
        // restore via renderRestore).
        val protectedStmt = stmt("AlterSequence")
        val followUpStmt = stmt("alter-seq-followup")
        val segment = AtomicPreserveSegment(
            batch = batch(
                protectedIds = listOf("AlterSequence"),
                followUpIds = listOf("alter-seq-followup"),
            ),
            statements = listOf(protectedStmt, followUpStmt),
        )

        val conn = mockk<Connection>(relaxed = true)
        val sqlsRun = mutableListOf<String>()
        val fakeStmt = mockk<Statement>(relaxed = true)
        every { conn.createStatement() } returns fakeStmt
        every { fakeStmt.execute(any<String>()) } answers {
            sqlsRun.add(firstArg())
            true
        }

        SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(segment),
            plainExecutor = { _, _, _ -> error("must not be called") },
            atomicRunner = { _, _, _, executeProtectedOps, _, _ ->
                // Invoke the runner-built callback against our mocked
                // connection to verify the filter.
                val result = executeProtectedOps(JdbcDatabaseConnection(conn), segment.batch.protectedOperationIds)
                result shouldBe AtomicProtectedExecutionResult.Succeeded(statementsExecuted = 1)
                AtomicSequencePreserveResult.Applied(listOf(pgSeq))
            },
        )
        // Only the protected SQL was executed; the follow-up was
        // filtered out before reaching the connection.
        sqlsRun shouldBe listOf(protectedStmt.sql)
    }

    test("multi-segment [Plain, Atomic, Plain] all execute when all succeed") {
        val plainHead = PlainSqlSegment(listOf(stmt("h1"), stmt("h2")))
        val atomic = AtomicPreserveSegment(batch = batch(), statements = listOf(stmt("AlterSequence")))
        val plainTail = PlainSqlSegment(listOf(stmt("t1")))
        val callOrder = mutableListOf<String>()
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(plainHead, atomic, plainTail),
            plainExecutor = { _, s, _ ->
                callOrder.add("plain(${s.first().operationIds.first()})")
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = s.size,
                    lastStatementOperationIds = s.last().operationIds,
                )
            },
            atomicRunner = { _, _, _, _, _, _ ->
                callOrder.add("atomic")
                AtomicSequencePreserveResult.Applied(listOf(pgSeq))
            },
        )
        callOrder shouldBe listOf("plain(h1)", "atomic", "plain(t1)")
        trace.executionCompleted shouldBe true
        // 2 (plainHead) + 1 (atomic) + 1 (plainTail) = 4
        trace.statementsAttempted shouldBe 4
        trace.lastStatementOperationIds shouldBe setOf("t1")
    }

    test("ein Abschnitt ausserhalb der Transaktion laeuft ueber denselben Ausfuehrer") {
        val head = PlainSqlSegment(listOf(stmt("h1")))
        val outside = NoTransactionSegment(listOf(stmt("ft", "CREATE FULLTEXT INDEX ON [docs] ([body]);")))
        val seen = mutableListOf<String>()

        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(head, outside),
            plainExecutor = { _, statements, _ ->
                seen += statements.first().operationIds.first()
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = statements.size,
                    lastStatementOperationIds = statements.last().operationIds,
                )
            },
            atomicRunner = { _, _, _, _, _, _ -> error("no atomic segment in this plan") },
        )

        seen shouldBe listOf("h1", "ft")
        trace.executionCompleted shouldBe true
    }

    test("scheitert ein spaeterer Abschnitt, bleibt der fruehere stehen") {
        // Der zurueckgerollte Abschnitt meldet fuer sich einen sauberen
        // Rueckbau. Was davor committet wurde, ist damit nicht weg — ohne die
        // Aufsummierung meldete der Lauf einen Zustand, den es nicht gibt.
        val head = PlainSqlSegment(listOf(stmt("h1")))
        val tail = PlainSqlSegment(listOf(stmt("t1")))

        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(head, tail),
            plainExecutor = { _, statements, _ ->
                if (statements.first().operationIds.first() == "h1") {
                    ExecutionTrace(
                        executionStarted = true,
                        executionCompleted = true,
                        statementsAttempted = 1,
                        lastStatementOperationIds = setOf("h1"),
                    )
                } else {
                    ExecutionTrace(
                        executionStarted = true,
                        executionCompleted = false,
                        statementsAttempted = 1,
                        transactionRolledBack = true,
                        sideEffectsPossible = false,
                        executionError = "boom",
                    )
                }
            },
            atomicRunner = { _, _, _, _, _, _ -> error("no atomic segment in this plan") },
        )

        trace.executionCompleted shouldBe false
        trace.transactionRolledBack shouldBe true
        trace.sideEffectsPossible shouldBe true
    }

    test("multi-segment short-circuits on the first failing segment") {
        val plainHead = PlainSqlSegment(listOf(stmt("h1")))
        val atomic = AtomicPreserveSegment(batch = batch(), statements = listOf(stmt("AlterSequence")))
        val plainTail = PlainSqlSegment(listOf(stmt("t1")))
        val callOrder = mutableListOf<String>()
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(plainHead, atomic, plainTail),
            plainExecutor = { _, s, _ ->
                val id = s.first().operationIds.first()
                callOrder.add("plain($id)")
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = true,
                    statementsAttempted = s.size,
                    lastStatementOperationIds = s.last().operationIds,
                )
            },
            atomicRunner = { _, _, _, _, _, _ ->
                callOrder.add("atomic")
                AtomicSequencePreserveResult.LockTimeout(listOf(pgSeq))
            },
        )
        // plainTail was NOT executed — the atomic segment short-
        // circuited the loop.
        callOrder shouldBe listOf("plain(h1)", "atomic")
        trace.executionCompleted shouldBe false
        trace.transactionRolledBack shouldBe true
        trace.executionError!! shouldContain "SEQUENCE_PRESERVE_LOCK_TIMEOUT"
    }

    test("plain segment failure short-circuits subsequent atomic segments") {
        val plainHead = PlainSqlSegment(listOf(stmt("h1")))
        val atomic = AtomicPreserveSegment(batch = batch(), statements = listOf(stmt("AlterSequence")))
        val callOrder = mutableListOf<String>()
        val trace = SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(plainHead, atomic),
            plainExecutor = { _, s, _ ->
                callOrder.add("plain(${s.first().operationIds.first()})")
                ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = false,
                    statementsAttempted = 0,
                    transactionRolledBack = true,
                    executionError = "syntax error near 'h1'",
                    recoverability = ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED,
                )
            },
            atomicRunner = { _, _, _, _, _, _ ->
                callOrder.add("atomic")
                error("atomic must not be called after a failing plain segment")
            },
        )
        callOrder shouldBe listOf("plain(h1)")
        trace.transactionRolledBack shouldBe true
        trace.executionError shouldBe "syntax error near 'h1'"
    }

    test("default lockTimeoutMillis flows through to the atomic runner") {
        val segment = AtomicPreserveSegment(batch = batch(), statements = listOf(stmt("AlterSequence")))
        var capturedTimeout: Long = -1
        SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(segment),
            plainExecutor = { _, _, _ -> error("must not be called") },
            atomicRunner = { _, _, _, _, lockTimeoutMillis, _ ->
                capturedTimeout = lockTimeoutMillis
                AtomicSequencePreserveResult.Applied(listOf(pgSeq))
            },
        )
        capturedTimeout shouldBe AtomicSequencePreserveRunner.DEFAULT_LOCK_TIMEOUT_MILLIS
    }

    test("executeWithDefaults forwards args to execute with production-default plainExecutor + atomicRunner") {
        // SchemaMigrateWiring uses
        // SegmentAwareMigrationExecutor::executeWithDefaults as the
        // executor method reference. The thin wrapper delegates to
        // execute(...) with production defaults; here we cover its body
        // by invoking it directly. The bogus alias triggers the
        // default atomicRunner's CompareConfigException path, which is
        // sufficient evidence the wrapper called through.
        val segment = AtomicPreserveSegment(
            batch = batch(),
            statements = listOf(stmt("AlterSequence")),
        )
        io.kotest.assertions.throwables.shouldThrow<CompareConfigException> {
            SegmentAwareMigrationExecutor.executeWithDefaults(
                target = CompareOperand.Database("unknown-alias-executeWithDefaults"),
                configPath = null,
                segments = listOf(segment),
                lockTimeoutMillis = AtomicSequencePreserveRunner.DEFAULT_LOCK_TIMEOUT_MILLIS,
                cancellationToken = dev.dmigrate.core.cancel.CancellationToken.none(),
            )
        }
    }

    test("default atomicRunner delegates to AtomicSequencePreserveRunner (production wiring)") {
        // Coverage gap: the `atomicRunner` default `::defaultAtomicRunner`
        // wraps AtomicSequencePreserveRunner.execute. Tests that always
        // override atomicRunner leave this private function uncovered.
        // Here we exercise the default by omitting the override; the
        // bogus target source surfaces as CompareConfigException via the
        // production-default acquireConnection path.
        val segment = AtomicPreserveSegment(
            batch = batch(),
            statements = listOf(stmt("AlterSequence")),
        )
        val ex = io.kotest.assertions.throwables.shouldThrow<CompareConfigException> {
            SegmentAwareMigrationExecutor.execute(
                target = CompareOperand.Database("unknown-alias-for-default-atomic-runner-test"),
                configPath = null,
                segments = listOf(segment),
                plainExecutor = { _, _, _ -> error("must not be called") },
                // Note: NO atomicRunner override — uses defaultAtomicRunner.
            )
        }
        // The CompareConfigException originates from
        // AtomicSequencePreserveRunner.defaultAcquireConnection
        // (NamedConnectionResolver failure). Mere presence proves the
        // default atomicRunner ran (otherwise no exception would surface).
        ex.message shouldNotBe null
    }

    test("custom lockTimeoutMillis is propagated to the atomic runner") {
        val segment = AtomicPreserveSegment(batch = batch(), statements = listOf(stmt("AlterSequence")))
        var capturedTimeout: Long = -1
        SegmentAwareMigrationExecutor.execute(
            target = target,
            configPath = null,
            segments = listOf(segment),
            lockTimeoutMillis = 7777L,
            plainExecutor = { _, _, _ -> error("must not be called") },
            atomicRunner = { _, _, _, _, lockTimeoutMillis, _ ->
                capturedTimeout = lockTimeoutMillis
                AtomicSequencePreserveResult.Applied(listOf(pgSeq))
            },
        )
        capturedTimeout shouldBe 7777L
    }
})
