package dev.dmigrate.server.application.job

import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.server.application.quota.OwnerAwareQuotaService
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.JobTransitionOutcome
import dev.dmigrate.server.ports.WorkerHandleRegistry
import java.time.Duration
import java.time.Instant

/**
 * Phase E §7.8 `job_cancel`-Service.
 *
 * Verkapselt die kompletten Cancel-Regeln aus Plan §5.6 + §7.8 und
 * liefert ein sealed [JobCancelOutcome], das der MCP-Tool-Handler aus
 * AP E.8 (2/3) auf den `job_cancel`-Wire mappt. Dispatcher-Wechselwirkung:
 *
 * - QUEUED-Cancel ist eine atomare CAS `QUEUED -> CANCELLED` ohne
 *   Worker-Ack (Plan §7.8 line 1212-1216). Die Dispatcher-Barriere
 *   aus AP E.7 (1/6) (`transitionStatus(allowed=QUEUED)` schlaegt fuer
 *   bereits-terminale Jobs fehl) verhindert die spaetere Worker-
 *   Ausfuehrung automatisch.
 * - RUNNING-Cancel laeuft zweistufig (Plan §7.2): erst `markCancelRequested`
 *   durabel, dann Worker-Handle-Signal. Antwort ist [AckPending] —
 *   die endgueltige Status-Transition `RUNNING -> CANCELLED` macht der
 *   Dispatcher beim Worker-Outcome.
 * - Reason wird scrubbed + laengenbegrenzt persistiert (Plan §7.8
 *   line 1223). Ein bereits durabel gespeicherter Reason wird nicht
 *   ueberschrieben (Plan §7.8 line 1224-1226 + §7.2-Idempotenz).
 *
 * Identitaets-/Tenant-Aufloesung gemaess Plan §5.6:
 *
 * - Tenant-scoped Job-`resourceUri`: Tenant aus URI; muss zu
 *   `effectiveTenantId` ODER `allowedTenantIds` passen, sonst
 *   [TenantScopeDenied].
 * - Opake `jobId`: Lookup nur im `effectiveTenantId` (kein Cross-Tenant-
 *   Probe). Nicht gefunden -> [NotFound]; gefunden mit fremdem Principal
 *   ohne Admin -> [ForbiddenPrincipal].
 *
 * Bewusst NICHT in dieser AP-Stufe:
 *
 * - Worker-Ack-Polling mit `cancelAckTimeout` (Plan §7.8 line 1216-1217):
 *   die aktuelle Antwort ist immer sofort [AckPending] mit
 *   [DEFAULT_RETRY_AFTER]. Polling-Variante kann in einer Folge-AP via
 *   optionalem `awaitAck`-Parameter nachgezogen werden.
 * - Audit-Event-Emission (Plan §7.8 "auditieren") landet in AP E.10.
 */
class JobCancelService(
    private val jobStore: JobStore,
    private val workerHandleRegistry: WorkerHandleRegistry,
    private val cancelReasonScrubber: (String) -> String = SecretScrubber::scrub,
    private val maxReasonLength: Int = DEFAULT_MAX_REASON_LENGTH,
    private val ackPendingRetryAfter: Duration = DEFAULT_RETRY_AFTER,
    /**
     * Phase E §7.9: optionaler owner-aware Quota-Service. Wird auf
     * `releaseForOwner` gerufen bei `QUEUED -> CANCELLED`-CAS, weil
     * der Dispatcher fuer queued-Jobs nie laeuft und somit kein
     * Terminal-Release ausloest. Plan §7.9 line 1291-1292.
     *
     * Fuer RUNNING-Cancel macht der Dispatcher den Release beim
     * Worker-Outcome — der Service hier ruft NICHT zusaetzlich, sonst
     * Doppel-Release.
     */
    private val quotaService: OwnerAwareQuotaService? = null,
) {

    fun cancel(
        jobIdOrUri: String,
        principal: PrincipalContext,
        reason: String?,
        now: Instant,
        signalSource: String = SIGNAL_SOURCE_JOB_CANCEL,
    ): JobCancelOutcome {
        val target = resolveTarget(jobIdOrUri, principal)
            ?: return JobCancelOutcome.NotFound(jobIdOrUri)
        if (target is TargetResolution.TenantScopeDenied) {
            return JobCancelOutcome.TenantScopeDenied(target.tenantId)
        }
        target as TargetResolution.Resolved
        val record = jobStore.findById(target.tenantId, target.jobId)
            ?: return JobCancelOutcome.NotFound(jobIdOrUri)
        if (!isAuthorized(record, principal)) {
            return JobCancelOutcome.ForbiddenPrincipal(record)
        }
        val scrubbedReason = reason?.let { scrubAndTruncate(it) }
        return when (record.managedJob.status) {
            JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED ->
                JobCancelOutcome.AlreadyTerminal(record)
            JobStatus.QUEUED -> cancelQueuedJob(record, principal, scrubbedReason, signalSource, now)
            JobStatus.RUNNING -> cancelRunningJob(record, principal, scrubbedReason, signalSource, now)
        }
    }

    private fun resolveTarget(
        jobIdOrUri: String,
        principal: PrincipalContext,
    ): TargetResolution? {
        if (!jobIdOrUri.startsWith(URI_PREFIX)) {
            // Opake jobId: tenant-lokaler Lookup nur im effectiveTenantId
            // (Plan §5.6 line 668-670).
            return TargetResolution.Resolved(principal.effectiveTenantId, jobIdOrUri)
        }
        return when (val parsed = ServerResourceUri.parse(jobIdOrUri)) {
            is ResourceUriParseResult.Invalid -> null
            is ResourceUriParseResult.Valid -> {
                if (parsed.uri.kind != ResourceKind.JOBS) return null
                val targetTenant = parsed.uri.tenantId
                if (targetTenant != principal.effectiveTenantId &&
                    targetTenant !in principal.allowedTenantIds
                ) {
                    TargetResolution.TenantScopeDenied(targetTenant)
                } else {
                    TargetResolution.Resolved(targetTenant, parsed.uri.id)
                }
            }
        }
    }

    private fun isAuthorized(record: JobRecord, principal: PrincipalContext): Boolean {
        if (record.ownerPrincipalId == principal.principalId) return true
        if (principal.isAdmin) return true
        return false
    }

    private fun cancelQueuedJob(
        record: JobRecord,
        principal: PrincipalContext,
        scrubbedReason: String?,
        signalSource: String,
        now: Instant,
    ): JobCancelOutcome {
        val outcome = jobStore.transitionStatus(
            tenantId = record.tenantId,
            jobId = record.managedJob.jobId,
            allowedFromStatuses = setOf(JobStatus.QUEUED),
        ) { mj ->
            mj.copy(
                status = JobStatus.CANCELLED,
                updatedAt = now,
                cancelRequest = mj.cancelRequest.copy(
                    requested = true,
                    requestedAt = mj.cancelRequest.requestedAt ?: now,
                    requestedBy = mj.cancelRequest.requestedBy ?: principal.principalId.value,
                    requestedReason = mj.cancelRequest.requestedReason ?: scrubbedReason,
                    signalSource = mj.cancelRequest.signalSource ?: signalSource,
                    signalAcked = true,
                    ackedAt = now,
                ),
            )
        }
        return when (outcome) {
            is JobTransitionOutcome.Applied -> {
                // Plan §7.9 line 1291-1292: Slot freigeben — fuer
                // queued-Cancel passiert das hier (Dispatcher laeuft nie).
                outcome.record.quotaReservationOwnerId?.let { ownerId ->
                    quotaService?.releaseForOwner(ownerId, now)
                }
                JobCancelOutcome.Cancelled(outcome.record)
            }
            is JobTransitionOutcome.IllegalTransition ->
                // Race: zwischen Lookup und CAS hat ein Worker den Status
                // geaendert. Re-read und als AlreadyTerminal/AckPending
                // melden, damit der Caller einen konsistenten Status sieht.
                rereadAfterRace(record)
            is JobTransitionOutcome.NotFound -> JobCancelOutcome.NotFound(record.managedJob.jobId)
        }
    }

    private fun cancelRunningJob(
        record: JobRecord,
        principal: PrincipalContext,
        scrubbedReason: String?,
        signalSource: String,
        now: Instant,
    ): JobCancelOutcome {
        // Plan §7.2: erst markCancelRequested durabel, dann Worker
        // signalisieren. Idempotenz: der erste Reason gewinnt — der
        // Store ueberschreibt nicht.
        val markOutcome = jobStore.markCancelRequested(
            tenantId = record.tenantId,
            jobId = record.managedJob.jobId,
            requestedAt = now,
            requestedBy = principal.principalId.value,
            signalSource = signalSource,
            reason = scrubbedReason,
        )
        return when (markOutcome) {
            is JobTransitionOutcome.Applied -> {
                workerHandleRegistry.signal(record.managedJob.jobId, scrubbedReason)
                // Antwort sofort als AckPending — die endgueltige
                // Statustransition macht der Dispatcher beim Worker-
                // Outcome (E.7 (1/6)).
                JobCancelOutcome.AckPending(markOutcome.record, ackPendingRetryAfter)
            }
            is JobTransitionOutcome.IllegalTransition ->
                JobCancelOutcome.AlreadyTerminal(record)
            is JobTransitionOutcome.NotFound -> JobCancelOutcome.NotFound(record.managedJob.jobId)
        }
    }

    private fun rereadAfterRace(record: JobRecord): JobCancelOutcome {
        val current = jobStore.findById(record.tenantId, record.managedJob.jobId)
            ?: return JobCancelOutcome.NotFound(record.managedJob.jobId)
        return when (current.managedJob.status) {
            JobStatus.QUEUED -> JobCancelOutcome.AckPending(current, ackPendingRetryAfter)
            JobStatus.RUNNING -> JobCancelOutcome.AckPending(current, ackPendingRetryAfter)
            JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELLED ->
                JobCancelOutcome.AlreadyTerminal(current)
        }
    }

    private fun scrubAndTruncate(reason: String): String {
        val scrubbed = cancelReasonScrubber(reason)
        return if (scrubbed.length > maxReasonLength) scrubbed.take(maxReasonLength) else scrubbed
    }

    private sealed interface TargetResolution {
        data class Resolved(val tenantId: TenantId, val jobId: String) : TargetResolution
        data class TenantScopeDenied(val tenantId: TenantId) : TargetResolution
    }

    companion object {
        const val SIGNAL_SOURCE_JOB_CANCEL: String = "job_cancel"
        const val DEFAULT_MAX_REASON_LENGTH: Int = 256
        val DEFAULT_RETRY_AFTER: Duration = Duration.ofSeconds(2)
        private const val URI_PREFIX: String = "dmigrate://tenants/"
    }
}

/**
 * Phase E §7.8 Job-Cancel-Outcome. Tool-Handler aus AP E.8 (2/3) mappt
 * jeden Branch auf den `job_cancel`-Wire (jobId, status, terminal,
 * resourceUri, executionMeta) bzw. einen Error-Envelope.
 */
sealed interface JobCancelOutcome {

    /**
     * QUEUED-Job per CAS direkt terminalisiert oder RUNNING-Job mit
     * sofortigem Worker-Ack (z.B. wenn Worker-Handle bereits beendet
     * hat). [record] traegt den finalen `CANCELLED`-Status.
     */
    data class Cancelled(val record: JobRecord) : JobCancelOutcome

    /**
     * Plan §5.6 / §7.8: Cancel ist durable angefordert, Worker hat
     * (noch) nicht bestaetigt. Tool-Handler projiziert
     * `executionMeta.cancelRequested=true` + `cancelAckPending=true`
     * + `retryAfter` aus [retryAfter] in Sekunden.
     */
    data class AckPending(val record: JobRecord, val retryAfter: Duration) : JobCancelOutcome

    /** Job ist bereits in einem terminalen Zustand. Cancel ist no-op. */
    data class AlreadyTerminal(val record: JobRecord) : JobCancelOutcome

    /**
     * No-oracle Fehler: unbekannte ID, cross-tenant `jobId`, oder URI
     * mit unbekanntem Job-Slot. Der Tool-Handler emittiert
     * `RESOURCE_NOT_FOUND` ohne Disambiguierung (Plan §5.6 line 661-662).
     */
    data class NotFound(val target: String) : JobCancelOutcome

    /**
     * Same-tenant Job, aber fremder Principal ohne Admin-Recht. Plan
     * §5.6 line 672-675.
     */
    data class ForbiddenPrincipal(val record: JobRecord) : JobCancelOutcome

    /**
     * Tenant-scoped URI ausserhalb `allowedTenantIds`. Plan §5.6 line
     * 663-665 — `TENANT_SCOPE_DENIED`.
     */
    data class TenantScopeDenied(val targetTenant: TenantId) : JobCancelOutcome
}
