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
            // Plan §7.2 verbietet "sichtbaren Job ohne Idempotency-
            // Commit". Reihenfolge: erst Idempotency committen, dann
            // den Job speichern. Wenn die Idempotency-Transition nicht
            // greift (Scope unreserved oder bereits committed), wird
            // der Job nie gespeichert — kein Halbzustand.
            //
            // Production-Adapter (SQL-backed) nutzen eine gemeinsame
            // DB-Transaktion und können in beliebiger Reihenfolge
            // arbeiten. Für InMemory garantiert das `synchronized`-
            // Lock zusammen mit dieser Reihenfolge die geforderte
            // jointly-visibility.
            val idempotencyApplied = idempotencyStore.commit(
                scope = idempotencyScope,
                resultRef = jobRecord.managedJob.jobId,
                now = now,
                retentionUntil = jobRecord.managedJob.expiresAt,
            )
            if (!idempotencyApplied) {
                return JobStartTransactionOutcome.IdempotencyNotEligible
            }
            // `JobStore.save` ist für InMemory unfehlbar; production-
            // Adapter würden hier in derselben DB-Transaktion sitzen
            // und beide Updates atomar rollback-fähig halten.
            val saved = jobStore.save(jobRecord)
            return JobStartTransactionOutcome.Committed(saved)
        }
    }
}
