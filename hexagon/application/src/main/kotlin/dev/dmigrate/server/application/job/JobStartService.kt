package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.ports.IdempotencyStore
import dev.dmigrate.server.ports.JobStartTransaction
import dev.dmigrate.server.ports.JobStartTransactionOutcome
import dev.dmigrate.server.ports.WorkerHandleRegistry
import java.time.Instant

/**
 * LF-012 / LN-011 / LN-017 / LN-027 *
 * Orchestriert die gemeinsame Start-Pipeline für die drei Read-Start-Tools
 * (`schema_reverse_start`, `data_profile_start`, `schema_compare_start`):
 *
 * 1. `IdempotencyStore.reserve(...)` mit Payload-Fingerprint.
 * 2. Wenn `Reserved`: neuen `jobId` allokieren, `JobRecord`-`QUEUED`-
 *    Variante über [jobBuilder] erzeugen, dann atomar via
 *    [JobStartTransaction.commit] persistieren.
 * 3. `WorkerHandleRegistry.register(jobId, source)` für späteres
 *    Cancel-Signal über [WorkerHandleRegistry.signal] (LF-012 / LN-011 / LN-017 / LN-027).
 * 4. Wenn bereits `Committed`: deduplizierte Antwort mit existierendem
 *    `jobId` aus `IdempotencyReserveOutcome.Committed.resultRef`.
 *
 * Andere Idempotency-Outcomes (`ExistingPending`, `AwaitingApproval`,
 * `Denied`, `Conflict`) liefert der Service als
 * [JobStartOutcome.Pending], [JobStartOutcome.AwaitingApproval],
 * [JobStartOutcome.Denied] und [JobStartOutcome.Conflict] zurück. Die
 * konkrete Tool-/MCP-Mapping-Logik (Wartezeiten, Approval-Token-
 * Challenges, Conflict-Response) lebt in den Tool-Handlern aus LF-012 / LN-011 / LN-017 / LN-027.
 *
 * LF-012 / LN-011 / LN-017 / LN-027:
 *
 * - Policy-Service-Aufruf (LF-012 / LN-011 / LN-017 / LN-027) — der Caller (Tool-Handler) muss
 *   `claimApproved` anrufen, bevor er `start(...)` ruft, falls eine
 *   Approval-Challenge offen war.
 * - Quota-/Rate-Limit-Pruefung (LF-012 / LN-011 / LN-017 / LN-027) — Caller-Verantwortung.
 * - Timeout-Budget (LF-012 / LN-011 / LN-017 / LN-027) — Caller-Verantwortung.
 *
 * Design: kleine Service-Oberfläche, keine versteckten Side-Effects.
 * Die LF-012 / LN-011 / LN-017 / LN-027-Tool-Handler komponieren `JobStartService` mit
 * `PolicyService`, `QuotaService` und `PayloadFingerprintService`.
 */
class JobStartService(
    private val idempotencyStore: IdempotencyStore,
    private val jobStartTransaction: JobStartTransaction,
    private val workerHandleRegistry: WorkerHandleRegistry,
    private val jobIdFactory: () -> String,
    private val cancellationSourceFactory: () -> CancellationTokenSource =
        { CancellationTokenSource.create() },
) {

    /**
     * Reserviert Idempotency, allokiert (oder dedupliziert) einen Job
     * und registriert eine Worker-Handle für späteres Cancel-Signaling.
     *
     * @param scope eindeutige Idempotency-Scope (tenant + caller + tool +
     *   key); wird für Duplikat-Erkennung genutzt.
     * @param payloadFingerprint stabiler Hash des Tool-Payloads. Bei
     *   Konflikt mit einer bestehenden Reservierung wird
     *   [JobStartOutcome.Conflict] geliefert.
     * @param now Zeitstempel für Idempotency-Expiry und Job-`createdAt`/
     *   `updatedAt`.
     * @param jobBuilder erzeugt den `JobRecord`-`QUEUED`-Snapshot aus
     *   dem allokierten `jobId` und [now]. Caller setzt `expiresAt`,
     *   `tenantId`, `ownerPrincipalId`, `visibility`, `resourceUri` und
     *   weitere fachliche Felder.
     */
    fun start(
        scope: IdempotencyScope,
        payloadFingerprint: String,
        now: Instant,
        jobBuilder: (jobId: String, createdAt: Instant) -> JobRecord,
    ): JobStartOutcome {
        return when (val reserve = idempotencyStore.reserve(scope, payloadFingerprint, now)) {
            is IdempotencyReserveOutcome.Reserved -> startNewJob(scope, now, jobBuilder)
            is IdempotencyReserveOutcome.Committed -> JobStartOutcome.AlreadyStarted(reserve.resultRef)
            is IdempotencyReserveOutcome.ExistingPending -> JobStartOutcome.Pending(reserve.leaseExpiresAt)
            is IdempotencyReserveOutcome.AwaitingApproval -> JobStartOutcome.AwaitingApproval(reserve.expiresAt)
            is IdempotencyReserveOutcome.Denied -> JobStartOutcome.Denied(reserve.reason, reserve.expiresAt)
            is IdempotencyReserveOutcome.Failed -> JobStartOutcome.Failed(reserve.reason, reserve.expiresAt)
            is IdempotencyReserveOutcome.Conflict -> JobStartOutcome.Conflict(reserve.existingFingerprint)
        }
    }

    private fun startNewJob(
        scope: IdempotencyScope,
        now: Instant,
        jobBuilder: (jobId: String, createdAt: Instant) -> JobRecord,
    ): JobStartOutcome {
        val jobId = jobIdFactory()
        val record = jobBuilder(jobId, now)
        return when (val outcome = jobStartTransaction.commit(record, scope, now)) {
            is JobStartTransactionOutcome.Committed -> {
                val source = cancellationSourceFactory()
                workerHandleRegistry.register(jobId, source)
                JobStartOutcome.Started(jobId, outcome.record, source)
            }
            is JobStartTransactionOutcome.IdempotencyNotEligible ->
                // The atomic transaction detected a parallel commit between
                // reserve and commit. Re-check via reserve so the caller
                // observes the deduplicated COMMITTED outcome on retry.
                JobStartOutcome.Pending(now.plusSeconds(1))
        }
    }
}

/**
 * LF-012 / LN-011 / LN-017 / LN-027 * mappen jeden Branch auf den passenden MCP-Response (jobId,
 * `IDEMPOTENCY_CONFLICT`, `POLICY_REQUIRED`, `RATE_LIMITED`,
 * `OPERATION_TIMEOUT`).
 */
sealed interface JobStartOutcome {

    /** Ein neuer Job ist `QUEUED` und in beiden Stores committed. */
    data class Started(
        val jobId: String,
        val record: JobRecord,
        val cancellationSource: CancellationTokenSource,
    ) : JobStartOutcome

    /** Idempotency-Match: Job existiert bereits, gibt jobId zurück. */
    data class AlreadyStarted(val jobId: String) : JobStartOutcome

    /** Aktive Reservierung existiert; Caller wartet/retried. */
    data class Pending(val leaseExpiresAt: Instant) : JobStartOutcome

    /** Policy-Approval ist offen; Caller liefert Approval-Token nach. */
    data class AwaitingApproval(val expiresAt: Instant) : JobStartOutcome

    /** Reservierung wurde explizit abgelehnt. */
    data class Denied(val reason: String, val expiresAt: Instant) : JobStartOutcome

    /**
     * Endgültige, nicht-retrybare Reservierung ohne Job (LF-012 / LN-011 / LN-017 / LN-027).
     * Identische Retries liefern deterministisch dieselbe Antwort bis
     * [expiresAt].
     */
    data class Failed(val reason: String, val expiresAt: Instant) : JobStartOutcome

    /** Payload-Fingerprint stimmt nicht mit Bestands-Reservierung überein. */
    data class Conflict(val existingFingerprint: String) : JobStartOutcome

    /**
     * LF-012 / LN-011 / LN-017 / LN-027: aktive
     * Jobquote ueberschritten. ApprovedRetryService produziert das, wenn
     * `quota.reserve` vor `JobStartTransaction.commit` ablehnt.
     * JobStartService selbst erzeugt diesen Branch nicht — Quota-
     * Pruefung lebt im Orchestrator/ApprovedRetry.
     */
    data class RateLimited(
        val retryAfter: java.time.Duration,
        val current: Long,
        val limit: Long,
        /**
         * LF-012 / LN-011 / LN-017 / LN-027: Diskriminator zwischen
         * Tenant-/Caller-Quota (`ACTIVE_JOBS_QUOTA`, Default — Bestands-
         * Pfad) und Executor-Pool-Saturation (`EXECUTOR_SATURATED`).
         * Wire-Caller sehen das Feld immer in den `RATE_LIMITED`-Details.
         */
        val reason: String = JobStartReason.ACTIVE_JOBS_QUOTA,
    ) : JobStartOutcome
}
