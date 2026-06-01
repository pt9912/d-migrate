package dev.dmigrate.driver.migration.preserve

import dev.dmigrate.core.diff.migration.SequenceObjectRef
import dev.dmigrate.driver.ProtectedOperationId
import dev.dmigrate.driver.SequenceCurrentValueProbeResult
import java.sql.Connection

/**
 * Atomic-Preserve Phase B (2026-05-31): execute-time orchestration
 * port that wraps Probe → protected sequence-bearing operations →
 * Restore in a single dialect-specific transaction.
 *
 * Plan-Doc: `docs/planning/in-progress/sequence-preserve-atomic-lock-plan.md`
 * §3.1 (in-scope contract) and §5 Phase B (port shape).
 *
 * Lifecycle inside [execute]:
 *
 * 1. **BEGIN** — open a transaction on the supplied [Connection].
 *    The connection must be a single owner; the executor refuses
 *    connections already in an enclosing transaction.
 * 2. **Lock + Probe** — acquire the per-dialect lock with the
 *    [lockTimeoutMillis] budget, then run the probe against
 *    [AtomicSequencePreserveRequest.sequenceRef] (and any siblings
 *    in [AtomicSequencePreserveBatch.requests]) in the canonical
 *    sorted order. The lock + probe-set step is all-or-nothing: if
 *    any sequence times out or fails, the executor rolls back the
 *    whole batch with `LockTimeout` / `Failed`.
 * 3. **Protected operations** — invoke [executeProtectedOperations]
 *    on the SAME connection. The runner-supplied lambda issues the
 *    actual sequence-bearing operations between Probe and Restore;
 *    the executor itself never inspects nor renders them.
 * 4. **Restore** — for every successful probe, call
 *    [AtomicSequencePreserveRequest.renderRestore] with the
 *    `Read` result and execute the returned statements against the
 *    locked connection.
 * 5. **COMMIT** on success, **ROLLBACK** on any error (lock timeout,
 *    probe failure, protected-operation exception, restore failure).
 *    Either every sequence in the batch lands a restored
 *    `next_value`, or none.
 *
 * For dialects whose lock timeout is session-scoped (MySQL
 * `innodb_lock_wait_timeout`, SQLite `busy_timeout`), the executor
 * MUST reset the timeout to its previous value in a `finally` block
 * so a pooled connection does not leak the override into the next
 * borrow.
 */
interface AtomicSequencePreserveExecutor {

    fun execute(
        connection: Connection,
        batch: AtomicSequencePreserveBatch,
        lockTimeoutMillis: Long,
        executeProtectedOperations: (Connection, List<ProtectedOperationId>) -> AtomicProtectedExecutionResult,
    ): AtomicSequencePreserveResult

    companion object {

        /**
         * Atomic-Preserve Phase C.3 (2026-06-01): connection-owner
         * contract check. Every [execute] implementation MUST call
         * this helper at entry so a wiring bug — passing a
         * connection that already lives in an enclosing transaction
         * (autoCommit=false) — surfaces as
         * [IllegalStateException] **before** any lock or probe runs.
         *
         * Throwing here is intentional and is NOT routed through
         * [AtomicSequencePreserveResult.Failed]: the failure is a
         * composition-root bug (the runner allocated a non-owned
         * connection) rather than a runtime fault. Surfacing it as
         * `Failed` would let the runner translate it into a planner
         * blocker, masking the real wiring problem.
         */
        fun requireOwnedConnection(connection: Connection) {
            check(connection.autoCommit) {
                "AtomicSequencePreserveExecutor requires an owned, " +
                    "non-enclosed connection (autoCommit=true at " +
                    "entry; got autoCommit=false meaning an " +
                    "enclosing transaction)."
            }
        }
    }
}

/**
 * One batch of preserve requests, the protected operation IDs the
 * runner expects to execute between Probe and Restore, and the
 * runner-internal follow-up IDs that pin the augmented plan's
 * deklarative `AlterSequenceCurrentValue` follow-ups to this batch.
 *
 * [requests] is the ordered list of sequences to preserve. The
 * executor sorts the list by [SequenceObjectRef.name] (optionally
 * schema-qualified) before locking to enforce a deterministic lock
 * order across parallel migrations (deadlock-diamond avoidance —
 * Plan-Doc §2 (3)).
 *
 * [protectedOperationIds] are the operations the runner will issue
 * inside [AtomicSequencePreserveExecutor.execute]'s
 * `executeProtectedOperations` callback. The executor cross-checks
 * the IDs against the dialect's
 * `SequenceCapability.transactionalProtectedSequenceOperations`
 * allowlist; a non-matching ID surfaces as
 * `AtomicSequencePreserveResult.Failed` with
 * `SEQUENCE_PRESERVE_ATOMIC_UNSUPPORTED`.
 *
 * [internalFollowUpIds] is the set of runner-internal operation IDs
 * that map back to this batch's `AlterSequenceCurrentValue`
 * follow-ups in the augmented plan. The list is opaque to the
 * executor — it exists so the runner can mark them
 * "already-applied" after a successful commit without re-rendering
 * them as standalone SQL.
 */
data class AtomicSequencePreserveBatch(
    val requests: List<AtomicSequencePreserveRequest>,
    val protectedOperationIds: List<ProtectedOperationId>,
    val internalFollowUpIds: List<String>,
)

/**
 * One sequence's preserve request: the target sequence + a render
 * callback that turns the probe result into the dialect-specific
 * restore SQL.
 *
 * The render callback is owned by the dialect's renderer (PG emits
 * `setval`, MySQL/SQLite emit `UPDATE dmg_sequences …`); the
 * executor only calls it once the probe has succeeded and never
 * inspects the returned statements beyond passing them to the
 * locked connection.
 */
data class AtomicSequencePreserveRequest(
    val sequenceRef: SequenceObjectRef,
    val renderRestore: (SequenceCurrentValueProbeResult.Read) -> List<String>,
)
