package dev.dmigrate.server.ports

import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.job.JobRecord
import java.time.Instant

/**
 * Phase E §5.1 / §7.2 Atomic Job-Start-Unit-of-Work.
 *
 * Bundles two updates into a single atomic operation:
 *
 * 1. [JobStore.save] of the new [JobRecord] (status `QUEUED`).
 * 2. [IdempotencyStore.commit] of the calling [IdempotencyScope] with
 *    `resultRef = jobRecord.managedJob.jobId` and
 *    `retentionUntil = jobRecord.managedJob.expiresAt`.
 *
 * Plan §5.1 / §7.2 forbid Saga-style sequencing where the job becomes
 * visible without the matching `COMMITTED`-Idempotency entry; this port
 * is the single approved primitive for that boundary.
 *
 * The InMemory adapter uses a process-level lock; production adapters
 * MUST provide an equivalent atomic primitive (shared transaction or
 * shared store). Plan §7.2 explicitly forbids "recoverable Saga mit
 * sichtbarem Job-ohne-Idempotency-Commit". Implementoren MUESSEN die
 * `JobStartTransactionContractTests`-Suite durchlaufen — Detail-
 * Vertrag in `spec/phase-e-port-atomicity.md` Abschnitt (3).
 *
 * The caller (typically `JobStartService`) is expected to have:
 * - reserved (or claimed) the [IdempotencyScope] before calling this
 *   transaction (via [IdempotencyStore.reserve] or
 *   [IdempotencyStore.claimApproved]).
 * - generated a fresh `jobId` via the
 *   [dev.dmigrate.server.core.identifier.IdGenerator].
 *
 * The transaction itself does NOT call `reserve`/`claimApproved`; it
 * assumes the [IdempotencyScope] is already in `PENDING` (post-reserve)
 * or `PENDING+claimed=true` (post-approval).
 */
interface JobStartTransaction {

    fun commit(
        jobRecord: JobRecord,
        idempotencyScope: IdempotencyScope,
        now: Instant,
    ): JobStartTransactionOutcome
}

/**
 * Phase E §7.2 outcome of [JobStartTransaction.commit].
 */
sealed interface JobStartTransactionOutcome {

    /** Both stores committed atomically. */
    data class Committed(val record: JobRecord) : JobStartTransactionOutcome

    /**
     * The Idempotency entry was not in an eligible state (`PENDING` or
     * `AWAITING_APPROVAL`); typically because the caller skipped the
     * reserve step or because a parallel caller already committed. The
     * caller MUST re-check via [IdempotencyStore.reserve] which returns
     * the deduplicated outcome.
     *
     * No job is saved when this outcome is returned.
     */
    data object IdempotencyNotEligible : JobStartTransactionOutcome
}
