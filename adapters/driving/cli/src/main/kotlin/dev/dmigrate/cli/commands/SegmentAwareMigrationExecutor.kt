package dev.dmigrate.cli.commands

import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.migration.ExecutionRecoverability
import dev.dmigrate.driver.migration.MigrationDdlStatement
import dev.dmigrate.driver.migration.MigrationExecutionStatementGroup
import dev.dmigrate.driver.migration.preserve.AtomicPreserveSegment
import dev.dmigrate.driver.migration.preserve.AtomicProtectedExecutionResult
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveBatch
import dev.dmigrate.driver.migration.preserve.AtomicSequencePreserveResult
import dev.dmigrate.driver.migration.preserve.ExecutableSegment
import dev.dmigrate.driver.migration.preserve.PlainSqlSegment
import java.nio.file.Path
import java.sql.Connection

/**
 * Functional signature of the plain-segment executor — same shape as
 * [JdbcMigrationExecutor.execute]. Top-level so [SegmentAwareMigrationExecutor]
 * can default it to the production executor; tests substitute a
 * recording lambda.
 */
internal typealias PlainExecutorFn = (
    target: CompareOperand.Database,
    statements: List<MigrationDdlStatement>,
    configPath: Path?,
) -> ExecutionTrace

/**
 * Functional signature of the atomic-preserve runner — wraps
 * [AtomicSequencePreserveRunner.execute] hiding its default parameters
 * so tests can substitute a fake.
 */
internal typealias AtomicRunnerFn = (
    target: CompareOperand.Database,
    configPath: Path?,
    batch: AtomicSequencePreserveBatch,
    executeProtectedOperations: (Connection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
    lockTimeoutMillis: Long,
) -> AtomicSequencePreserveResult

/**
 * Atomic-Preserve Phase C.3 (2026-06-01): segment-aware execute
 * runner that consumes a [List]&lt;[ExecutableSegment]&gt;, delegating
 * each segment to the right executor.
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase C / Sub-Slice C.3.
 *
 * Pre-C.1 the stage does not yet supply an [AtomicSequencePreserveBatch],
 * so [segmentForExecute] (Phase C.2) degenerates to a single
 * [PlainSqlSegment] and this runner forwards it to [JdbcMigrationExecutor]
 * unchanged — the heutige Live-Preserve-IT remains green. Once
 * Sub-Slice C.1 lands, the stage emits [AtomicPreserveSegment]s
 * containing the protected statements + the per-sequence batch, and
 * this runner routes them to [AtomicSequencePreserveRunner].
 *
 * The runner stops execution at the first failing segment
 * (`transactionRolledBack` or non-null `executionError`) and returns a
 * trace marked accordingly; subsequent segments are not executed.
 */
internal object SegmentAwareMigrationExecutor {

    fun execute(
        target: CompareOperand.Database,
        configPath: Path?,
        segments: List<ExecutableSegment>,
        lockTimeoutMillis: Long = AtomicSequencePreserveRunner.DEFAULT_LOCK_TIMEOUT_MILLIS,
        plainExecutor: PlainExecutorFn = JdbcMigrationExecutor::execute,
        atomicRunner: AtomicRunnerFn = ::defaultAtomicRunner,
    ): ExecutionTrace {
        if (segments.isEmpty()) {
            return ExecutionTrace(
                executionStarted = true,
                executionCompleted = true,
                statementsAttempted = 0,
            )
        }
        var attempted = 0
        var lastOpIds: Set<String> = emptySet()
        val groups = mutableListOf<MigrationExecutionStatementGroup>()
        for (segment in segments) {
            val segmentTrace = when (segment) {
                is PlainSqlSegment -> plainExecutor(target, segment.statements, configPath)
                is AtomicPreserveSegment -> runAtomicSegment(
                    target = target,
                    configPath = configPath,
                    segment = segment,
                    atomicRunner = atomicRunner,
                    lockTimeoutMillis = lockTimeoutMillis,
                )
            }
            attempted += segmentTrace.statementsAttempted
            if (segmentTrace.lastStatementOperationIds.isNotEmpty()) {
                lastOpIds = segmentTrace.lastStatementOperationIds
            }
            groups.addAll(segmentTrace.statementGroups)
            if (segmentTrace.transactionRolledBack || segmentTrace.executionError != null) {
                return ExecutionTrace(
                    executionStarted = true,
                    executionCompleted = false,
                    statementsAttempted = attempted,
                    lastStatementOperationIds = lastOpIds,
                    transactionRolledBack = segmentTrace.transactionRolledBack,
                    sideEffectsPossible = segmentTrace.sideEffectsPossible,
                    executionError = segmentTrace.executionError,
                    statementGroups = groups,
                    recoverability = segmentTrace.recoverability,
                )
            }
        }
        return ExecutionTrace(
            executionStarted = true,
            executionCompleted = true,
            statementsAttempted = attempted,
            lastStatementOperationIds = lastOpIds,
            statementGroups = groups,
        )
    }

    private fun runAtomicSegment(
        target: CompareOperand.Database,
        configPath: Path?,
        segment: AtomicPreserveSegment,
        atomicRunner: AtomicRunnerFn,
        lockTimeoutMillis: Long,
    ): ExecutionTrace {
        // Internal follow-ups (the AlterSequenceCurrentValue audit
        // markers) are NOT executed standalone in the live-execute
        // path — the atomic executor handles the restore via each
        // request's renderRestore callback. The segment still carries
        // them so plan-only / report / rollback artefacts can show
        // the audit trail; here we filter them out before handing the
        // remaining protected statements to the executor's callback.
        val followUpIds: Set<String> = segment.batch.internalFollowUpIds.toSet()
        val protectedStatements = segment.statements.filter { stmt ->
            stmt.operationIds.none { it in followUpIds }
        }
        val executeProtectedOps: (Connection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult =
            { connection, _ ->
                for (stmt in protectedStatements) {
                    connection.createStatement().use { it.execute(stmt.sql) }
                }
                AtomicProtectedExecutionResult.Succeeded(statementsExecuted = protectedStatements.size)
            }
        val result = atomicRunner(
            target,
            configPath,
            segment.batch,
            executeProtectedOps,
            lockTimeoutMillis,
        )
        return mapAtomicResultToTrace(result = result, segment = segment)
    }

    private fun mapAtomicResultToTrace(
        result: AtomicSequencePreserveResult,
        segment: AtomicPreserveSegment,
    ): ExecutionTrace = when (result) {
        is AtomicSequencePreserveResult.Applied -> ExecutionTrace(
            executionStarted = true,
            executionCompleted = true,
            statementsAttempted = segment.statements.size,
            lastStatementOperationIds = segment.statements.lastOrNull()?.operationIds ?: emptySet(),
        )
        is AtomicSequencePreserveResult.NotFound -> ExecutionTrace(
            executionStarted = true,
            executionCompleted = false,
            statementsAttempted = 0,
            transactionRolledBack = true,
            executionError = "Atomic preserve aborted — sequence(s) not found: " +
                result.refs.joinToString(", ") { it.name },
            recoverability = ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED,
        )
        is AtomicSequencePreserveResult.LockTimeout -> ExecutionTrace(
            executionStarted = true,
            executionCompleted = false,
            statementsAttempted = 0,
            transactionRolledBack = true,
            executionError = "SEQUENCE_PRESERVE_LOCK_TIMEOUT for: " +
                result.refs.joinToString(", ") { it.name },
            recoverability = ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED,
        )
        is AtomicSequencePreserveResult.Failed -> ExecutionTrace(
            executionStarted = true,
            executionCompleted = false,
            statementsAttempted = 0,
            transactionRolledBack = true,
            executionError = "Atomic preserve failed for ${result.ref.name}: " +
                (result.cause.message ?: result.cause::class.java.simpleName),
            recoverability = ExecutionRecoverability.FULL_ROLLBACK_CONFIRMED,
        )
    }

    private fun defaultAtomicRunner(
        target: CompareOperand.Database,
        configPath: Path?,
        batch: AtomicSequencePreserveBatch,
        executeProtectedOperations: (Connection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
        lockTimeoutMillis: Long,
    ): AtomicSequencePreserveResult = AtomicSequencePreserveRunner.execute(
        target = target,
        configPath = configPath,
        batch = batch,
        executeProtectedOperations = executeProtectedOperations,
        lockTimeoutMillis = lockTimeoutMillis,
    )

    /**
     * Production [SegmentAwareExecutorFn] entry point — exposes [execute]
     * with production-default `plainExecutor` and `atomicRunner` via a
     * 4-arg signature that matches the typealias directly (method
     * reference compatible). [SchemaMigrateWiring] uses
     * `SegmentAwareMigrationExecutor::executeWithDefaults` instead of an
     * inline lambda so the wiring stays one method-reference line and
     * the delegate is unit-testable on its own.
     */
    fun executeWithDefaults(
        target: CompareOperand.Database,
        configPath: Path?,
        segments: List<ExecutableSegment>,
        lockTimeoutMillis: Long,
    ): ExecutionTrace = execute(
        target = target,
        configPath = configPath,
        segments = segments,
        lockTimeoutMillis = lockTimeoutMillis,
    )
}
