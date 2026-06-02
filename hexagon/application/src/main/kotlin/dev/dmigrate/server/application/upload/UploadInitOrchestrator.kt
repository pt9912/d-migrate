package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.approval.ApprovalAttempt
import dev.dmigrate.server.application.approval.ApprovalGrantService
import dev.dmigrate.server.application.approval.ApprovalGrantValidation
import dev.dmigrate.server.application.approval.ApprovalTokenFingerprint
import dev.dmigrate.server.application.policy.PolicyAttempt
import dev.dmigrate.server.application.policy.PolicyService
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.application.quota.RateLimitedDetail
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome
import dev.dmigrate.server.core.idempotency.SyncEffectScope
import dev.dmigrate.server.core.policy.PolicyDecision
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.SyncEffectIdempotencyStore
import dev.dmigrate.server.ports.UploadInitClaimOutcome
import dev.dmigrate.server.ports.UploadInitClaimScope
import dev.dmigrate.server.ports.UploadInitClaimStore
import dev.dmigrate.server.ports.UploadSessionStore
import dev.dmigrate.server.ports.ApprovalGrantStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * LF-010 / LF-013 / LN-009 / LN-011— Orchestrator fuer den
 * policy-pflichtigen `artifact_upload_init`-Pfad.
 *
 * Pipeline:
 *
 * 1. Pre-store Validation (`approvalKey` Pflicht, `sizeBytes`-
 *    Konsistenz, optionale `targetTable`-Anwesenheit nur fuer
 *    `uploadIntent=job_input`).
 * 2. Approval-Fingerprint via [UploadInitApprovalFingerprint].
 * 3. [SyncEffectIdempotencyStore.reserve] mit Scope
 *    `(tenant, caller, artifact_upload_init, approvalKey)`:
 *    - `Existing(resultRef)` -> `AlreadyInitialized` (Replay).
 *    - `Conflict` -> `IdempotencyConflict`.
 *    - `Reserved` -> Pipeline weiter.
 * 4. [UploadInitClaimStore.acquire] mit gleichem Scope + Fingerprint:
 *    - `InProgress` -> Caller wartet (`UploadInitOutcome.InProgress`).
 *    - `Conflict` -> `IdempotencyConflict`.
 *    - `Acquired`/`Reclaimed` -> Pipeline weiter.
 * 5. [PolicyService.decide]:
 *    - `Denied` -> Claim freigeben, `PolicyDenied`.
 *    - `RequiresApproval` -> Claim freigeben, `PolicyRequired` ohne
 *      Session/Quota (LF-010 / LF-013 / LN-009 / LN-011 "no-side-effect").
 *    - `Allowed` -> Session durabel erzeugen.
 * 6. [UploadSessionStore.save] mit `approvalKey`/`approvalFingerprint`/
 *    `targetTable` durabel auf der Session.
 * 7. `SyncEffectIdempotencyStore.commit(scope, sessionId)` und
 *    `UploadInitClaimStore.release(scope, claimId)` schliessen den
 *    Single-Writer-Claim ab.
 *
 * Quota-Integration: nach erfolgreicher Policy-Entscheidung und vor dem
 * durablem Session-Commit werden `ACTIVE_UPLOAD_SESSIONS` und `UPLOAD_BYTES`
 * reserviert. Erst nach Session- und SyncEffect-Commit werden die
 * Reservierungen committed; bei Fehlern vor diesem Punkt werden sie
 * refunded.
 */
class UploadInitOrchestrator(
    private val syncEffectStore: SyncEffectIdempotencyStore,
    private val claimStore: UploadInitClaimStore,
    private val sessionStore: UploadSessionStore,
    private val policyService: PolicyService,
    private val approvalFingerprintService: UploadInitApprovalFingerprint,
    private val sessionIdFactory: () -> String = { "up-${UUID.randomUUID()}" },
    private val claimIdFactory: () -> String = { "claim-${UUID.randomUUID()}" },
    private val claimLeaseDuration: Duration = DEFAULT_CLAIM_LEASE,
    private val sessionInitialTtl: Duration = DEFAULT_INITIAL_TTL,
    private val sessionIdleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    private val sessionAbsoluteLease: Duration = DEFAULT_ABSOLUTE_LEASE,
    private val quotaService: QuotaService? = null,
    private val approvalGrantStore: ApprovalGrantStore? = null,
    private val approvalGrantService: ApprovalGrantService? = null,
) {

    fun init(request: UploadInitRequest): UploadInitOutcome {
        validate(request)?.let { return it }

        val fingerprint = approvalFingerprintService.fingerprint(request.attempt)
        val syncEffectScope = SyncEffectScope(
            tenantId = request.tenantId,
            callerId = request.callerId,
            toolName = TOOL_NAME,
            approvalKey = request.approvalKey,
        )

        return when (val reserve = syncEffectStore.reserve(syncEffectScope, fingerprint, request.now)) {
            is SyncEffectReserveOutcome.Existing ->
                UploadInitOutcome.AlreadyInitialized(
                    uploadSessionId = reserve.resultRef,
                    ttlSeconds = replayTtlSeconds(request, reserve.resultRef),
                )
            is SyncEffectReserveOutcome.Conflict ->
                UploadInitOutcome.IdempotencyConflict(reserve.existingFingerprint)
            is SyncEffectReserveOutcome.Reserved ->
                claimAndDecide(request, fingerprint)
        }
    }

    private fun validate(request: UploadInitRequest): UploadInitOutcome? {
        if (request.approvalKey.isBlank()) {
            return UploadInitOutcome.ValidationError("approvalKey is required for policy-pflichtiges init")
        }
        if (request.attempt.sizeBytes < 0) {
            return UploadInitOutcome.ValidationError("sizeBytes must be >= 0")
        }
        return null
    }

    private fun claimAndDecide(
        request: UploadInitRequest,
        fingerprint: String,
    ): UploadInitOutcome {
        val claimScope = UploadInitClaimScope(
            tenantId = request.tenantId,
            callerId = request.callerId,
            toolName = TOOL_NAME,
            approvalKey = request.approvalKey,
        )
        val newClaimId = claimIdFactory()
        val claimLeaseExpiresAt = request.now.plus(claimLeaseDuration)
        return when (val claim = claimStore.acquire(
            claimScope, fingerprint, newClaimId, claimLeaseExpiresAt, request.now,
        )) {
            is UploadInitClaimOutcome.Acquired -> decideAndMaterialize(request, fingerprint, claimScope, claim.claim.claimId)
            is UploadInitClaimOutcome.Reclaimed -> decideAndMaterialize(request, fingerprint, claimScope, claim.claim.claimId)
            is UploadInitClaimOutcome.InProgress ->
                UploadInitOutcome.InProgress(claimLeaseExpiresAt = claim.current.leaseExpiresAt)
            is UploadInitClaimOutcome.Conflict ->
                UploadInitOutcome.IdempotencyConflict(claim.existingFingerprint)
        }
    }

    private fun decideAndMaterialize(
        request: UploadInitRequest,
        fingerprint: String,
        claimScope: UploadInitClaimScope,
        claimId: String,
    ): UploadInitOutcome {
        val approvalToken = request.approvalToken
        if (approvalToken != null) {
            return when (val validation = validateApprovalToken(request, fingerprint, approvalToken)) {
                is ApprovalGrantValidation.Valid -> materializeSession(request, fingerprint, claimScope, claimId)
                is ApprovalGrantValidation.Invalid -> {
                    claimStore.release(claimScope, claimId)
                    UploadInitOutcome.PolicyDenied(invalidToReason(validation))
                }
            }
        }

        val attempt = PolicyAttempt(
            tenantId = request.tenantId,
            callerId = request.callerId,
            toolName = TOOL_NAME,
            correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
            correlationKey = request.approvalKey,
            payloadFingerprint = fingerprint,
        )
        return when (val decision = policyService.decide(attempt)) {
            is PolicyDecision.Denied -> {
                claimStore.release(claimScope, claimId)
                UploadInitOutcome.PolicyDenied(decision.reasonCode)
            }
            is PolicyDecision.RequiresApproval -> {
                // LF-010 / LF-013 / LN-009 / LN-011: POLICY_REQUIRED -> KEINE Session, KEINE
                // Upload-Berechtigung, KEINE Quota-Reservierung.
                claimStore.release(claimScope, claimId)
                UploadInitOutcome.PolicyRequired(
                    approvalRequestId = decision.approvalRequestId,
                    correlationKind = decision.correlationKind,
                    correlationKey = decision.correlationKey,
                    requiredScopes = decision.requiredScopes,
                    reasons = decision.reasons,
                )
            }
            PolicyDecision.Allowed -> materializeSession(request, fingerprint, claimScope, claimId)
        }
    }

    private fun validateApprovalToken(
        request: UploadInitRequest,
        fingerprint: String,
        rawToken: String,
    ): ApprovalGrantValidation {
        val grantService = approvalGrantService ?: return ApprovalGrantValidation.Invalid.Unknown
        val tokenFingerprint = ApprovalTokenFingerprint.compute(rawToken)
        val grant = approvalGrantStore?.findByTokenFingerprint(request.tenantId, tokenFingerprint)
            ?: return ApprovalGrantValidation.Invalid.Unknown
        return grantService.validate(
            ApprovalAttempt(
                tokenFingerprint = tokenFingerprint,
                approvalRequestId = grant.approvalRequestId,
                tenantId = request.tenantId,
                callerId = request.callerId,
                toolName = TOOL_NAME,
                correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
                correlationKey = request.approvalKey,
                payloadFingerprint = fingerprint,
                requiredScopes = grant.issuedScopes,
            ),
            request.now,
        )
    }

    private fun invalidToReason(invalid: ApprovalGrantValidation.Invalid): String = when (invalid) {
        ApprovalGrantValidation.Invalid.Unknown -> "policy:grant-unknown"
        ApprovalGrantValidation.Invalid.Expired -> "policy:grant-expired"
        ApprovalGrantValidation.Invalid.TenantMismatch -> "policy:tenant-mismatch"
        ApprovalGrantValidation.Invalid.CallerMismatch -> "policy:caller-mismatch"
        ApprovalGrantValidation.Invalid.ToolMismatch -> "policy:tool-mismatch"
        ApprovalGrantValidation.Invalid.ApprovalRequestIdMismatch -> "policy:approval-request-mismatch"
        ApprovalGrantValidation.Invalid.CorrelationMismatch -> "policy:correlation-mismatch"
        ApprovalGrantValidation.Invalid.PayloadMismatch -> "policy:payload-mismatch"
        is ApprovalGrantValidation.Invalid.ScopeMismatch -> "policy:scope-mismatch"
        ApprovalGrantValidation.Invalid.IssuerMismatch -> "policy:issuer-mismatch"
    }

    private fun materializeSession(
        request: UploadInitRequest,
        fingerprint: String,
        claimScope: UploadInitClaimScope,
        claimId: String,
    ): UploadInitOutcome {
        val quotaReservations = reserveInitQuotas(request)
        val sessionId = sessionIdFactory()
        val now = request.now
        val session = UploadSession(
            uploadSessionId = sessionId,
            tenantId = request.tenantId,
            ownerPrincipalId = request.callerId,
            resourceUri = ServerResourceUri(request.tenantId, ResourceKind.UPLOAD_SESSIONS, sessionId),
            artifactKind = request.attempt.artifactKind,
            mimeType = request.attempt.mimeType,
            sizeBytes = request.attempt.sizeBytes,
            segmentTotal = request.segmentTotal,
            checksumSha256 = request.attempt.checksumSha256,
            uploadIntent = request.attempt.uploadIntent,
            state = UploadSessionState.ACTIVE,
            createdAt = now,
            updatedAt = now,
            idleTimeoutAt = now.plus(sessionIdleTimeout),
            absoluteLeaseExpiresAt = now.plus(sessionAbsoluteLease),
            approvalKey = request.approvalKey,
            approvalFingerprint = fingerprint,
            targetTable = request.attempt.targetTable,
            wireArtifactKind = request.attempt.wireArtifactKind,
            bundleFormat = request.attempt.bundleFormat,
            intendedTables = request.attempt.intendedTables?.map { it.lowercase() }?.distinct()?.sorted(),
        )
        try {
            sessionStore.save(session)
            val syncEffectScope = SyncEffectScope(
                tenantId = request.tenantId,
                callerId = request.callerId,
                toolName = TOOL_NAME,
                approvalKey = request.approvalKey,
            )
            syncEffectStore.commit(syncEffectScope, sessionId, now)
            commitInitQuotas(quotaReservations)
            claimStore.release(claimScope, claimId)
        } catch (failure: RuntimeException) {
            refundInitQuotas(quotaReservations)
            throw failure
        }
        return UploadInitOutcome.Initialized(
            uploadSessionId = sessionId,
            ttlSeconds = effectiveTtlSeconds(now, session.absoluteLeaseExpiresAt),
            expectedFirstSegmentIndex = 1,
            expectedFirstSegmentOffset = 0,
        )
    }

    private fun replayTtlSeconds(request: UploadInitRequest, uploadSessionId: String): Long {
        val existing = sessionStore.findById(request.tenantId, uploadSessionId)
        return if (existing != null) {
            effectiveTtlSeconds(request.now, existing.absoluteLeaseExpiresAt)
        } else {
            sessionInitialTtl.seconds
        }
    }

    private fun effectiveTtlSeconds(now: Instant, absoluteLeaseExpiresAt: Instant): Long =
        minOf(sessionInitialTtl.seconds, Duration.between(now, absoluteLeaseExpiresAt).seconds).coerceAtLeast(0L)

    private data class InitQuotaReservations(
        val activeSession: QuotaReservation,
        val uploadBytes: QuotaReservation,
    )

    private fun reserveInitQuotas(request: UploadInitRequest): InitQuotaReservations? {
        val quota = quotaService ?: return null
        val sessionsKey = QuotaKey(
            tenantId = request.tenantId,
            dimension = QuotaDimension.ACTIVE_UPLOAD_SESSIONS,
            principalId = request.callerId,
        )
        val bytesKey = QuotaKey(
            tenantId = request.tenantId,
            dimension = QuotaDimension.UPLOAD_BYTES,
            principalId = request.callerId,
        )
        val activeSession = when (val outcome = quota.reserve(sessionsKey, amount = 1)) {
            is QuotaOutcome.Granted -> QuotaReservation.of(outcome)
            is QuotaOutcome.RateLimited -> return throwRateLimited(outcome)
        }
        val uploadBytes = when (val outcome = quota.reserve(bytesKey, amount = request.attempt.sizeBytes)) {
            is QuotaOutcome.Granted -> QuotaReservation.of(outcome)
            is QuotaOutcome.RateLimited -> {
                quota.refund(activeSession)
                return throwRateLimited(outcome)
            }
        }
        return InitQuotaReservations(activeSession, uploadBytes)
    }

    private fun commitInitQuotas(reservations: InitQuotaReservations?) {
        val quota = quotaService ?: return
        if (reservations == null) return
        quota.commit(reservations.activeSession)
        quota.commit(reservations.uploadBytes)
    }

    private fun refundInitQuotas(reservations: InitQuotaReservations?) {
        val quota = quotaService ?: return
        if (reservations == null) return
        quota.refund(reservations.uploadBytes)
        quota.refund(reservations.activeSession)
    }

    private fun throwRateLimited(outcome: QuotaOutcome.RateLimited): Nothing =
        throw dev.dmigrate.server.application.error.RateLimitedException(RateLimitedDetail.from(outcome))

    companion object {
        const val TOOL_NAME: String = "artifact_upload_init"
        val DEFAULT_CLAIM_LEASE: Duration = Duration.ofSeconds(60)
        val DEFAULT_INITIAL_TTL: Duration = Duration.ofSeconds(900)
        val DEFAULT_IDLE_TIMEOUT: Duration = Duration.ofSeconds(300)
        val DEFAULT_ABSOLUTE_LEASE: Duration = Duration.ofSeconds(3_600)
    }
}

data class UploadInitRequest(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val approvalKey: String,
    val attempt: UploadInitApprovalAttempt,
    val segmentTotal: Int = 1,
    val now: Instant,
    val approvalToken: String? = null,
)

sealed interface UploadInitOutcome {

    data class Initialized(
        val uploadSessionId: String,
        val ttlSeconds: Long,
        val expectedFirstSegmentIndex: Int,
        val expectedFirstSegmentOffset: Long,
    ) : UploadInitOutcome

    /** Idempotenter Replay — der Caller bekommt die bestehende `uploadSessionId`. */
    data class AlreadyInitialized(
        val uploadSessionId: String,
        val ttlSeconds: Long,
    ) : UploadInitOutcome

    /** Aktiver Single-Writer-Claim eines anderen Pipelines auf demselben Scope. */
    data class InProgress(val claimLeaseExpiresAt: Instant) : UploadInitOutcome

    /** Gleicher Scope, abweichender Payload — LF-010 / LF-013 / LN-009 / LN-011 IDEMPOTENCY_CONFLICT. */
    data class IdempotencyConflict(val existingFingerprint: String) : UploadInitOutcome

    /** LF-010 / LF-013 / LN-009 / LN-011: POLICY_REQUIRED ohne Session, ohne Berechtigung, ohne Quota. */
    data class PolicyRequired(
        val approvalRequestId: String,
        val correlationKind: ApprovalCorrelationKind,
        val correlationKey: String,
        val requiredScopes: Set<String>,
        val reasons: List<String>,
    ) : UploadInitOutcome

    /** Endgueltige Ablehnung; Caller bekommt `POLICY_DENIED`. */
    data class PolicyDenied(val reasonCode: String) : UploadInitOutcome

    /** Pre-Store Validation-Fehler — kein SyncEffect-/Claim-Eintrag entsteht. */
    data class ValidationError(val reason: String) : UploadInitOutcome
}
