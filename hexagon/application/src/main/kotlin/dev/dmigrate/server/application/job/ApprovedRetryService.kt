package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.server.application.approval.ApprovalAttempt
import dev.dmigrate.server.application.approval.ApprovalGrantService
import dev.dmigrate.server.application.approval.ApprovalGrantValidation
import dev.dmigrate.server.application.quota.OwnerAwareQuotaService
import dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.ports.IdempotencyStore
import dev.dmigrate.server.ports.JobStartTransaction
import dev.dmigrate.server.ports.JobStartTransactionOutcome
import dev.dmigrate.server.ports.WorkerHandleRegistry
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.time.Duration
import java.time.Instant

/**
 * Phase E §7.5 Approved-Retry-Orchestrator.
 *
 * Verbindet die drei atomaren Bausteine fuer den genehmigten Retry aus
 * `AWAITING_APPROVAL`:
 *
 * 1. Grant-Validierung ueber [ApprovalGrantService] gegen den
 *    [ApprovalAttempt]. Ungueltige Grants resultieren in
 *    [IdempotencyStore.deny] mit einem `policy:<grund>`-Reason und einer
 *    [JobStartOutcome.Denied]-Antwort.
 * 2. [IdempotencyStore.claimApproved] — atomare
 *    `AWAITING_APPROVAL -> PENDING(claimed)`-Transition. Erstgewinner darf
 *    Side-Effects ausfuehren; konkurrierende Retries sehen `AlreadyClaimed`
 *    (-> [JobStartOutcome.Pending]) bzw. `Committed` (-> [JobStartOutcome.AlreadyStarted]).
 * 3. [JobStartTransaction.commit] — atomare Job-Anlage + Idempotency-
 *    `COMMITTED(jobId)`-Transition; bei Erfolg wird die [WorkerHandleRegistry]
 *    fuer spaeteres Cancel-Signal verkabelt.
 *
 * Symmetrisch zu [JobStartService.start] gibt der Service [JobStartOutcome]
 * zurueck — die Tool-Handler aus AP E.6 koennen beide Pfade auf dieselben
 * Wire-Antworten mappen.
 */
class ApprovedRetryService(
    private val approvalGrantService: ApprovalGrantService,
    private val idempotencyStore: IdempotencyStore,
    private val jobStartTransaction: JobStartTransaction,
    private val workerHandleRegistry: WorkerHandleRegistry,
    private val jobIdFactory: () -> String,
    private val cancellationSourceFactory: () -> CancellationTokenSource =
        { CancellationTokenSource.create() },
    /**
     * Phase E §7.9 Quota-Integration (Review-Fix Blocker #2). Wenn null,
     * ueberspringt der ApprovedRetryService die Quota-Reservierung —
     * sinnvoll fuer Bestands-Tests. Production-Wiring (PhaseEWiring)
     * setzt eine echte Instanz. Approved-Retry erzeugt einen NEUEN Job
     * (Plan §7.9 line 1264) und MUSS daher die aktive-Job-Quota
     * reservieren, bevor JobStartTransaction.commit laeuft.
     */
    private val quotaService: OwnerAwareQuotaService? = null,
    private val quotaLeaseDuration: Duration = DEFAULT_QUOTA_LEASE,
) {

    fun retry(
        attempt: ApprovalAttempt,
        scope: IdempotencyScope,
        now: Instant,
        jobBuilder: (jobId: String, createdAt: Instant) -> JobRecord,
    ): JobStartOutcome {
        return when (val validation = approvalGrantService.validate(attempt, now)) {
            is ApprovalGrantValidation.Valid -> claimAndCommit(scope, now, jobBuilder)
            is ApprovalGrantValidation.Invalid -> denyReservation(scope, validation, now)
        }
    }

    private fun claimAndCommit(
        scope: IdempotencyScope,
        now: Instant,
        jobBuilder: (jobId: String, createdAt: Instant) -> JobRecord,
    ): JobStartOutcome = when (val claim = idempotencyStore.claimApproved(scope, now)) {
        is IdempotencyClaimOutcome.Claimed -> commitJob(scope, now, jobBuilder)
        is IdempotencyClaimOutcome.Committed -> JobStartOutcome.AlreadyStarted(claim.resultRef)
        is IdempotencyClaimOutcome.AlreadyClaimed -> JobStartOutcome.Pending(claim.leaseExpiresAt)
        is IdempotencyClaimOutcome.Denied -> JobStartOutcome.Denied(claim.reason, claim.expiresAt)
        is IdempotencyClaimOutcome.NotAwaitingApproval ->
            // Race: AWAITING_APPROVAL expired (-> Reserved-eligible) or never existed.
            // Surface as a non-retrybare Failed; the tool handler maps to OPERATION_TIMEOUT.
            JobStartOutcome.Failed(REASON_NOT_AWAITING_APPROVAL, now)
    }

    private fun commitJob(
        scope: IdempotencyScope,
        now: Instant,
        jobBuilder: (jobId: String, createdAt: Instant) -> JobRecord,
    ): JobStartOutcome {
        // Phase E §7.9 (Review-Fix Blocker #2): quota.reserve VOR commit.
        // Approved-Retry erzeugt einen NEUEN Job, also MUSS Quota
        // reserviert werden — analog zum primären-Start-Pfad im
        // Orchestrator.
        val ownerId = quotaService?.let { quotaOwnerIdFor(scope) }
        if (ownerId != null) {
            val key = QuotaKey(
                tenantId = scope.tenantId,
                dimension = QuotaDimension.ACTIVE_JOBS,
                principalId = scope.callerId,
                operation = scope.toolName,
            )
            val outcome = quotaService.reserve(
                key = key,
                amount = QUOTA_AMOUNT_PER_JOB,
                ownerId = ownerId,
                leaseExpiresAt = now.plus(quotaLeaseDuration),
                now = now,
            )
            if (outcome is QuotaOutcome.RateLimited) {
                return JobStartOutcome.RateLimited(
                    retryAfter = outcome.retryAfter,
                    current = outcome.current,
                    limit = outcome.limit,
                )
            }
        }

        val jobId = jobIdFactory()
        val baseRecord = jobBuilder(jobId, now)
        val record = if (ownerId != null) baseRecord.copy(quotaReservationOwnerId = ownerId) else baseRecord
        return when (val outcome = jobStartTransaction.commit(record, scope, now)) {
            is JobStartTransactionOutcome.Committed -> {
                if (ownerId != null) quotaService?.commitForOwner(ownerId, now)
                val source = cancellationSourceFactory()
                workerHandleRegistry.register(jobId, source)
                JobStartOutcome.Started(jobId, outcome.record, source)
            }
            is JobStartTransactionOutcome.IdempotencyNotEligible -> {
                if (ownerId != null) quotaService?.refundForOwner(ownerId, now)
                // Parallel commit between claim and transaction commit. Caller
                // retries -> claimApproved returns Committed and the dedup path
                // fires.
                JobStartOutcome.Pending(now.plusSeconds(1))
            }
        }
    }

    private fun quotaOwnerIdFor(scope: IdempotencyScope): String =
        "${scope.tenantId.value}:${scope.callerId.value}:${scope.toolName}:${scope.idempotencyKey.value}"

    private fun denyReservation(
        scope: IdempotencyScope,
        invalid: ApprovalGrantValidation.Invalid,
        now: Instant,
    ): JobStartOutcome {
        val reason = invalidToReason(invalid)
        val expiresAt = idempotencyStore.deny(scope, reason, now)
            ?: // Reservation already in a terminal state; re-claim to read it back.
            return when (val claim = idempotencyStore.claimApproved(scope, now)) {
                is IdempotencyClaimOutcome.Committed -> JobStartOutcome.AlreadyStarted(claim.resultRef)
                is IdempotencyClaimOutcome.Denied -> JobStartOutcome.Denied(claim.reason, claim.expiresAt)
                else -> JobStartOutcome.Failed(REASON_NOT_AWAITING_APPROVAL, now)
            }
        return JobStartOutcome.Denied(reason, expiresAt)
    }

    private fun invalidToReason(invalid: ApprovalGrantValidation.Invalid): String = when (invalid) {
        is ApprovalGrantValidation.Invalid.Unknown -> "policy:grant-unknown"
        is ApprovalGrantValidation.Invalid.Expired -> "policy:grant-expired"
        is ApprovalGrantValidation.Invalid.TenantMismatch -> "policy:tenant-mismatch"
        is ApprovalGrantValidation.Invalid.CallerMismatch -> "policy:caller-mismatch"
        is ApprovalGrantValidation.Invalid.ToolMismatch -> "policy:tool-mismatch"
        is ApprovalGrantValidation.Invalid.ApprovalRequestIdMismatch -> "policy:approval-request-mismatch"
        is ApprovalGrantValidation.Invalid.CorrelationMismatch -> "policy:correlation-mismatch"
        is ApprovalGrantValidation.Invalid.PayloadMismatch -> "policy:payload-mismatch"
        is ApprovalGrantValidation.Invalid.ScopeMismatch -> "policy:scope-mismatch"
        is ApprovalGrantValidation.Invalid.IssuerMismatch -> "policy:issuer-mismatch"
    }

    companion object {
        const val REASON_NOT_AWAITING_APPROVAL: String = "policy:not-awaiting-approval"
        const val QUOTA_AMOUNT_PER_JOB: Long = 1L
        val DEFAULT_QUOTA_LEASE: Duration = Duration.ofSeconds(60)
    }
}
