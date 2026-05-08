package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.application.policy.PolicyAttempt
import dev.dmigrate.server.application.policy.PolicyService
import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.application.upload.AbortApprovalAttempt
import dev.dmigrate.server.application.upload.AbortApprovalFingerprint
import dev.dmigrate.server.core.approval.ApprovalCorrelationKind
import dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome
import dev.dmigrate.server.core.idempotency.SyncEffectScope
import dev.dmigrate.server.core.policy.PolicyDecision
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.upload.AbortOutcome
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.AbortOutcomeStore
import dev.dmigrate.server.ports.AbortOutcomeStore.SaveOutcome
import dev.dmigrate.server.ports.SyncEffectIdempotencyStore
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import java.time.Clock

/**
 * LF-010 / LF-013 / LN-009 / LN-011 § 5.3 + § 8.6 (F.6 3/3) — Pipeline fuer administrative /
 * fremde `artifact_upload_abort`-Aufrufe.
 *
 * Pflicht-Sequence (Vertragswortlaut):
 *
 * 1. Pre-Abort-Approval-Fingerprint via [AbortApprovalFingerprint]
 *    aus Pre-Abort-Session-Zustand + Caller + Pre-Abort-Bytes +
 *    optional `reason`.
 * 2. [SyncEffectIdempotencyStore.reserve] mit Scope
 *    `(tenant, caller, artifact_upload_abort, approvalKey)`:
 *    - `Existing(resultRef)` -> Lookup im [AbortOutcomeStore];
 *      gleicher Fingerprint -> Outcome zurueckgeben, abweichend
 *      -> `IDEMPOTENCY_CONFLICT`.
 *    - `Conflict` -> `IDEMPOTENCY_CONFLICT`.
 *    - `Reserved` -> Pipeline weiter.
 * 3. [PolicyService.decide]:
 *    - `Allowed` -> Abort durchfuehren.
 *    - `RequiresApproval` -> `POLICY_REQUIRED`.
 *    - `Denied` -> `POLICY_DENIED`.
 * 4. Session-Status auf `ABORTED` transitionieren, Segmente
 *    loeschen, Init-Quotas freigeben (idempotent).
 * 5. [AbortOutcomeStore.save] mit dem berechneten Fingerprint;
 *    Conflict ist ein internes Drift-Signal (Vertrag: Save-Conflict
 *    -> der gespeicherte Outcome bleibt unangetastet, neuer
 *    Versuch liefert IDEMPOTENCY_CONFLICT).
 * 6. [SyncEffectIdempotencyStore.commit] mit dem `resultRef`
 *    erst nach durablem ABORTED + Cleanup + Quota-Release
 *    (LF-012 / LN-011 / LN-017 / LN-027 wortlaeufig).
 *
 * Der `resultRef` ist deterministisch der Pre-Abort-Fingerprint
 * selbst — gleicher Approval-Grant + gleicher Pre-Abort-Zustand
 * = gleicher resultRef = idempotenter Replay.
 */
internal class AdministrativeAbortPipeline(
    private val sessionStore: UploadSessionStore,
    private val segmentStore: UploadSegmentStore,
    private val quotaService: QuotaService,
    private val syncEffectStore: SyncEffectIdempotencyStore,
    private val abortOutcomeStore: AbortOutcomeStore,
    private val abortApprovalFingerprint: AbortApprovalFingerprint,
    private val policyService: PolicyService,
    private val clock: Clock,
) {

    /**
     * Fuehrt den administrativen Abort idempotent aus oder wirft die
     * passende `ApplicationException`. Rueckgabe ist der finale
     * `AbortOutcome` (egal ob frisch erzeugt oder aus dem Replay).
     */
    fun executeOrThrow(
        session: UploadSession,
        principal: PrincipalContext,
        approvalKey: String,
        reason: String?,
    ): AbortOutcome {
        val attempt = AbortApprovalAttempt(
            callerTenantId = principal.effectiveTenantId,
            callerId = principal.principalId,
            sessionTenantId = session.tenantId,
            sessionOwnerPrincipalId = session.ownerPrincipalId,
            uploadSessionId = session.uploadSessionId,
            preAbortState = session.state,
            artifactKind = session.artifactKind,
            uploadIntent = session.uploadIntent,
            preAbortBytes = session.bytesReceived,
            reason = reason,
        )
        val fingerprint = abortApprovalFingerprint.fingerprint(attempt)
        val scope = SyncEffectScope(
            tenantId = principal.effectiveTenantId,
            callerId = principal.principalId,
            toolName = AbortApprovalFingerprint.TOOL_NAME,
            approvalKey = approvalKey,
        )
        val now = clock.instant()
        return when (val reserve = syncEffectStore.reserve(scope, fingerprint, now)) {
            is SyncEffectReserveOutcome.Existing ->
                resolveReplay(reserve.resultRef, fingerprint)
            is SyncEffectReserveOutcome.Conflict ->
                throw IdempotencyConflictException(existingFingerprint = reserve.existingFingerprint)
            is SyncEffectReserveOutcome.Reserved ->
                decideAndAbort(session, principal, scope, attempt, fingerprint, reason)
        }
    }

    private fun resolveReplay(resultRef: String, expectedFingerprint: String): AbortOutcome {
        val stored = abortOutcomeStore.findByResultRef(resultRef)
            ?: throw IdempotencyConflictException(existingFingerprint = resultRef)
        if (stored.abortFingerprint != expectedFingerprint) {
            throw IdempotencyConflictException(existingFingerprint = stored.abortFingerprint)
        }
        return stored
    }

    private fun decideAndAbort(
        session: UploadSession,
        principal: PrincipalContext,
        scope: SyncEffectScope,
        attempt: AbortApprovalAttempt,
        fingerprint: String,
        reason: String?,
    ): AbortOutcome {
        val policyAttempt = PolicyAttempt(
            tenantId = principal.effectiveTenantId,
            callerId = principal.principalId,
            toolName = AbortApprovalFingerprint.TOOL_NAME,
            correlationKind = ApprovalCorrelationKind.APPROVAL_KEY,
            correlationKey = scope.approvalKey,
            payloadFingerprint = fingerprint,
        )
        return when (val decision = policyService.decide(policyAttempt)) {
            is PolicyDecision.RequiresApproval ->
                throw dev.dmigrate.server.application.error.PolicyRequiredException(
                    policyName = "${AbortApprovalFingerprint.TOOL_NAME}.${attempt.uploadSessionId}",
                )
            is PolicyDecision.Denied ->
                throw PolicyDeniedException(
                    policyName = AbortApprovalFingerprint.TOOL_NAME,
                    reason = decision.reasonCode,
                )
            PolicyDecision.Allowed ->
                materialiseAbort(session, scope, fingerprint, reason)
        }
    }

    private fun materialiseAbort(
        session: UploadSession,
        scope: SyncEffectScope,
        fingerprint: String,
        reason: String?,
    ): AbortOutcome {
        val now = clock.instant()
        val preAbortState = session.state
        val aborted = sessionStore.transitionOrThrow(session, UploadSessionState.ABORTED, now)
        // Best-effort cleanup; quota-release laeuft dennoch, sonst
        // bleibt der Tenant nach einem I/O-Fehler beim Segment-Delete
        // unfair belastet (Vertrag: Quota-Release idempotent).
        try {
            segmentStore.deleteAllForSession(session.uploadSessionId)
        } finally {
            releaseInitQuotas(session)
        }
        val outcome = AbortOutcome(
            abortFingerprint = fingerprint,
            uploadSessionId = session.uploadSessionId,
            preAbortState = preAbortState,
            terminalState = aborted.state,
            quotaReleased = true,
            completedAt = now,
            reason = reason,
        )
        // LF-012 / LN-011 / LN-017 / LN-027: terminaler Erfolgs-resultRef erst NACH durablem
        // ABORTED + Cleanup + Quota-Release. Save liefert Stored fuer
        // den ersten Versuch; AlreadyStored fuer einen Replay vom
        // gleichen Fingerprint; Conflict ist ein internes Drift-
        // Signal.
        when (val save = abortOutcomeStore.save(fingerprint, outcome)) {
            is SaveOutcome.Stored,
            is SaveOutcome.AlreadyStored -> Unit
            is SaveOutcome.Conflict -> throw IdempotencyConflictException(
                existingFingerprint = save.existingFingerprint,
            )
        }
        syncEffectStore.commit(scope, fingerprint, now)
        return outcome
    }

    private fun releaseInitQuotas(session: UploadSession) {
        // LF-012 / LN-011 / LN-017 / LN-027 / § 8.6: gleiche Quota-Keys wie Owner-Self-Abort
        // (ACTIVE_UPLOAD_SESSIONS=1, UPLOAD_BYTES=session.sizeBytes),
        // adressiert ueber den ORIGINALEN Session-Owner — der Caller
        // (Admin) hat die Reservierungen nicht selbst gemacht.
        quotaService.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.ACTIVE_UPLOAD_SESSIONS,
                    session.ownerPrincipalId,
                ),
                amount = 1,
            ),
        )
        quotaService.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.UPLOAD_BYTES,
                    session.ownerPrincipalId,
                ),
                amount = session.sizeBytes,
            ),
        )
    }
}
