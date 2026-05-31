package dev.dmigrate.driver.migration.preserve

import dev.dmigrate.core.diff.migration.SequenceObjectRef

/**
 * Atomic-Preserve Phase B (2026-05-31): sealed outcome of a
 * [AtomicSequencePreserveExecutor.execute] call. All non-`Applied`
 * subtypes mean the underlying transaction was rolled back; the
 * runner re-routes them onto the planner-blocker surface via
 * `PlannerBlockerClassifier`.
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §5 Phase B.
 */
sealed class AtomicSequencePreserveResult {

    /**
     * Probe + protected operations + Restore committed for every
     * sequence in the batch. [refs] enumerates the affected
     * sequences in the canonical sort order the executor used to
     * acquire locks.
     */
    data class Applied(val refs: List<SequenceObjectRef>) : AtomicSequencePreserveResult()

    /**
     * One or more sequences in the batch were missing in the
     * target database (PG sequence relation absent; MySQL / SQLite
     * `dmg_sequences` row absent). The whole batch rolled back —
     * none of the sequences in [refs] were restored. The runner
     * surfaces the partial set so the planner can attribute the
     * blocker to the offending sequences rather than the whole
     * batch generically.
     */
    data class NotFound(val refs: List<SequenceObjectRef>) : AtomicSequencePreserveResult()

    /**
     * The per-dialect lock did not acquire within
     * `lockTimeoutMillis` for one or more sequences in [refs]. The
     * transaction is rolled back; the runner emits
     * `SEQUENCE_PRESERVE_LOCK_TIMEOUT` (Classifier:
     * `MANUAL_ACTION_REQUIRED`) and exits without retry.
     */
    data class LockTimeout(val refs: List<SequenceObjectRef>) : AtomicSequencePreserveResult()

    /**
     * The transaction failed for a reason other than NotFound /
     * LockTimeout: protected-operation exception, restore-SQL
     * failure, JDBC-driver error. [ref] names the sequence the
     * executor was processing when the failure surfaced; [cause]
     * carries the underlying throwable for the planner-side
     * diagnostic message.
     */
    data class Failed(val ref: SequenceObjectRef, val cause: Throwable) : AtomicSequencePreserveResult()
}

/**
 * Runner-internal summary of the `executeProtectedOperations`
 * callback. Kept minimal — the executor only needs to know whether
 * the callback completed cleanly or threw, plus the count of
 * statements actually executed (for the report-side diagnostic).
 *
 * Exceptions thrown from the callback propagate to the executor as
 * a regular Throwable, which routes onto
 * [AtomicSequencePreserveResult.Failed]; [Succeeded] is the only
 * shape the executor itself synthesises.
 */
sealed class AtomicProtectedExecutionResult {
    data class Succeeded(val statementsExecuted: Int) : AtomicProtectedExecutionResult()
}
