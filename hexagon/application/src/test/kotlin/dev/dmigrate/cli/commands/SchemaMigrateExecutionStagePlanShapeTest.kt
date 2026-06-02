package dev.dmigrate.cli.commands

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.diff.migration.DiffPhase
import dev.dmigrate.core.diff.migration.OperationRisk
import dev.dmigrate.core.diff.migration.RenameProjectionDialect
import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.migration.MigrationDdlResult
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveRequest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Atomic-Preserve Phase D follow-up (2026-06-01, Finding #1): pins
 * that [SchemaMigrateExecutionStage.maybeExecute] catches
 * `IllegalStateException` from `segmentForExecute(...)` and turns it
 * into a structured `ExecutionTrace` instead of letting the CLI
 * crash with an uncaught planner-shape exception.
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §10 "Bekannte Carve-Outs / Folge-Themen" Finding #1.
 */
class SchemaMigrateExecutionStagePlanShapeTest : FunSpec({

    fun stage(executor: SegmentAwareExecutorFn?) = SchemaMigrateExecutionStage(
        executor = executor,
        dbLoader = null,
        normalizer = { it },
        fingerprint = { it.name + ":" + it.version },
        printError = { _, _ -> },
    )

    fun request() = SchemaMigrateRequest(
        source = "file:src",
        target = "db:test",
        dialect = DatabaseDialect.POSTGRESQL,
        execute = true,
    )

    val target = CompareOperand.Database("db:test")

    fun ddl(sql: String, opIds: Set<String>) = MigrationDdlStatement(
        sql = sql,
        operationIds = opIds,
        risk = OperationRisk.SAFE,
        phase = DiffPhase.TABLES,
    )

    test("non-contiguous atomic-preserve block: IllegalStateException is mapped to structured ExecutionTrace") {
        // Construct a contiguity violation: a plain statement sits
        // between two protected ops. `segmentForExecute` is contract-
        // bound to refuse this shape with IllegalStateException; the
        // stage MUST catch it and emit a structured trace instead of
        // propagating to the CLI top-level.
        val combined = MigrationDdlResult(
            statements = listOf(
                ddl("CREATE SEQUENCE a START WITH 1", setOf("op-a")),
                ddl("CREATE TABLE t (id int)", setOf("op-plain")),
                ddl("CREATE SEQUENCE b START WITH 1", setOf("op-b")),
            ),
            operationsRendered = setOf("op-a", "op-plain", "op-b"),
        )
        val batch = AtomicSequencePreserveBatch(
            requests = listOf(
                AtomicSequencePreserveRequest(
                    sequenceRef = SequenceObjectRef("a", null, RenameProjectionDialect.POSTGRESQL),
                    renderRestore = { _ -> emptyList() },
                ),
                AtomicSequencePreserveRequest(
                    sequenceRef = SequenceObjectRef("b", null, RenameProjectionDialect.POSTGRESQL),
                    renderRestore = { _ -> emptyList() },
                ),
            ),
            protectedOperationIds = listOf(
                ProtectedOperationId("op-a"),
                ProtectedOperationId("op-b"),
            ),
            internalFollowUpIds = emptyList(),
        )

        // The executor must NOT be reached — if segmentForExecute
        // throws before it runs, calling it would mean the catch
        // missed.
        val executor: SegmentAwareExecutorFn = { _, _, _, _, _ ->
            throw AssertionError("executor must not be reached on contiguity failure")
        }

        val trace = stage(executor).maybeExecute(
            request = request(),
            target = target,
            combined = combined,
            atomicBatch = batch,
            cancellationToken = CancellationTokenSource.create().token,
        )

        trace shouldNotBe null
        trace!!.executionStarted shouldBe false
        trace.executionCompleted shouldBe false
        trace.statementsAttempted shouldBe 0
        trace.transactionRolledBack shouldBe true
        trace.sideEffectsPossible shouldBe false
        trace.executionError shouldNotBe null
        trace.executionError!! shouldContain "Atomic-preserve plan shape invalid"
        // The diagnostic message must surface the contiguity-violation
        // index/range from segmentForExecute's `check` block so the
        // operator can map the failure back to a planner op-id.
        trace.executionError!! shouldContain "contiguous"
    }
})
