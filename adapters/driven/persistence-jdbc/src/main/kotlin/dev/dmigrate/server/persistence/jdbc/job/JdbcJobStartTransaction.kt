package dev.dmigrate.server.persistence.jdbc.job

import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.ports.JobStartTransaction
import dev.dmigrate.server.ports.JobStartTransactionOutcome
import java.time.Instant

/**
 * Postgres-/JDBC-Implementierung des [JobStartTransaction]-Vertrags.
 * Komponiert [JdbcIdempotencyStore.commitOnConnection] und
 * [JdbcJobStore.saveOnConnection] in einer einzigen DB-TX
 * (LF-012 / LN-011 / LN-017 / LN-027,
 * `spec/phase-e-port-atomicity.md` Abschnitt 3).
 *
 * Reihenfolge in der TX:
 *
 * 1. `IdempotencyStore.commit` (CAS auf PENDING/AWAITING_APPROVAL).
 * 2. Wenn applied: `JobStore.save`.
 * 3. Wenn nicht applied (Idempotency war nicht in eligible state):
 *    Outcome `IdempotencyNotEligible`; der `JdbcTransactionRunner`
 *    committet die TX trivial (kein JobStore-Insert).
 *
 * LF-012 / LN-011 / LN-017 / LN-027 verbietet Saga-Style-Sequencing — durch die geteilte
 * Connection wird die Commit-Boundary atomar: entweder beide
 * Updates sichtbar, oder keiner. Bei JVM-Crash oder DB-Disconnect
 * im Block rollbackt der Runner beide via `Connection.rollback()`.
 */
class JdbcJobStartTransaction(
    private val transactionRunner: JdbcTransactionRunner,
    private val idempotencyStore: JdbcIdempotencyStore,
    private val jobStore: JdbcJobStore,
) : JobStartTransaction {

    override fun commit(
        jobRecord: JobRecord,
        idempotencyScope: IdempotencyScope,
        now: Instant,
    ): JobStartTransactionOutcome = transactionRunner.inTransaction { conn ->
        val idempotencyApplied = idempotencyStore.commitOnConnection(
            conn = conn,
            scope = idempotencyScope,
            resultRef = jobRecord.managedJob.jobId,
            now = now,
            retentionUntil = jobRecord.managedJob.expiresAt,
        )
        if (!idempotencyApplied) {
            return@inTransaction JobStartTransactionOutcome.IdempotencyNotEligible
        }
        val saved = jobStore.saveOnConnection(conn, jobRecord)
        JobStartTransactionOutcome.Committed(saved)
    }
}
