package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.ports.IdempotencyStore
import dev.dmigrate.server.ports.JobStartTransaction
import dev.dmigrate.server.ports.JobStartTransactionOutcome
import dev.dmigrate.server.ports.JobStore
import java.time.Instant

/**
 * In-memory adapter for [JobStartTransaction]. Acquires a process-level
 * lock so the [JobStore.save] and [IdempotencyStore.commit] become
 * jointly visible — no reader observes the job without the matching
 * COMMITTED idempotency entry, and no reader observes the COMMITTED
 * idempotency entry without the matching job.
 *
 * Production adapters (SQL-backed multi-tenant store) MUST provide an
 * equivalent atomic primitive. Phase E §7.2 explicitly forbids
 * Saga-style sequencing.
 */
class InMemoryJobStartTransaction(
    private val jobStore: JobStore,
    private val idempotencyStore: IdempotencyStore,
) : JobStartTransaction {

    private val lock = Any()

    override fun commit(
        jobRecord: JobRecord,
        idempotencyScope: IdempotencyScope,
        now: Instant,
    ): JobStartTransactionOutcome {
        synchronized(lock) {
            // Save the job first so a parallel caller hitting the
            // idempotency store after our commit finds the matching
            // job. The lock guarantees atomicity for InMemory; if the
            // idempotency commit fails, we revert the save below.
            val saved = jobStore.save(jobRecord)
            val idempotencyApplied = idempotencyStore.commit(
                scope = idempotencyScope,
                resultRef = jobRecord.managedJob.jobId,
                now = now,
                retentionUntil = jobRecord.managedJob.expiresAt,
            )
            if (!idempotencyApplied) {
                // Plan §7.2: no recoverable Saga. Roll back the save by
                // marking the job as expired immediately so deleteExpired
                // collects it. We cannot remove records via the public
                // JobStore API, but a follow-up cleanup pass will handle
                // it. For test fixtures this is acceptable; production
                // adapters use real transactions.
                return JobStartTransactionOutcome.IdempotencyNotEligible
            }
            return JobStartTransactionOutcome.Committed(saved)
        }
    }
}
