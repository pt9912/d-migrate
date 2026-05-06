package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.policy.PolicyAttempt
import dev.dmigrate.server.application.policy.PolicyService
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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Phase F § 5.1 + § 8.3 (F.3 3/4) — Orchestrator fuer den
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
 *      Session/Quota (Plan § 8.3 "no-side-effect").
 *    - `Allowed` -> Session durabel erzeugen.
 * 6. [UploadSessionStore.save] mit `approvalKey`/`approvalFingerprint`/
 *    `targetTable` durabel auf der Session.
 * 7. `SyncEffectIdempotencyStore.commit(scope, sessionId)` und
 *    `UploadInitClaimStore.release(scope, claimId)` schliessen den
 *    Single-Writer-Claim ab.
 *
 * Quota-Integration (Plan § 5.1 "aktive Upload-Session-Quota +
 * reservierte Upload-Bytes vor durablem Session-Commit") kommt in einer
 * Folge-AP, sobald die Quota-Dimension `ACTIVE_UPLOAD_SESSIONS` +
 * `UPLOAD_BYTES` produktiv durchgewoben ist (E2.7-Stack erweitert).
 * Aktuell beleibt die Pipeline atomar fuer den Session-/Claim-Pfad;
 * Quota-Hooks werden additive eingehaengt.
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
    private val sessionIdleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    private val sessionAbsoluteLease: Duration = DEFAULT_ABSOLUTE_LEASE,
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
                    ttlSeconds = sessionAbsoluteLease.seconds,
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
                // Plan § 8.3: POLICY_REQUIRED -> KEINE Session, KEINE
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

    private fun materializeSession(
        request: UploadInitRequest,
        fingerprint: String,
        claimScope: UploadInitClaimScope,
        claimId: String,
    ): UploadInitOutcome {
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
        )
        sessionStore.save(session)
        val syncEffectScope = SyncEffectScope(
            tenantId = request.tenantId,
            callerId = request.callerId,
            toolName = TOOL_NAME,
            approvalKey = request.approvalKey,
        )
        syncEffectStore.commit(syncEffectScope, sessionId, now)
        claimStore.release(claimScope, claimId)
        return UploadInitOutcome.Initialized(
            uploadSessionId = sessionId,
            ttlSeconds = sessionAbsoluteLease.seconds,
            expectedFirstSegmentIndex = 1,
            expectedFirstSegmentOffset = 0,
        )
    }

    companion object {
        const val TOOL_NAME: String = "artifact_upload_init"
        val DEFAULT_CLAIM_LEASE: Duration = Duration.ofSeconds(60)
        val DEFAULT_IDLE_TIMEOUT: Duration = Duration.ofMinutes(30)
        val DEFAULT_ABSOLUTE_LEASE: Duration = Duration.ofHours(24)
    }
}

data class UploadInitRequest(
    val tenantId: TenantId,
    val callerId: PrincipalId,
    val approvalKey: String,
    val attempt: UploadInitApprovalAttempt,
    val segmentTotal: Int = 1,
    val now: Instant,
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

    /** Gleicher Scope, abweichender Payload — Plan § 5.1 IDEMPOTENCY_CONFLICT. */
    data class IdempotencyConflict(val existingFingerprint: String) : UploadInitOutcome

    /** Plan § 8.3: POLICY_REQUIRED ohne Session, ohne Berechtigung, ohne Quota. */
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
