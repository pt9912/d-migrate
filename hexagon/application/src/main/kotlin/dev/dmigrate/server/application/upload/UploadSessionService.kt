package dev.dmigrate.server.application.upload

import dev.dmigrate.server.application.quota.QuotaReservation
import dev.dmigrate.server.application.quota.QuotaService
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.FinalizationOutcome
import dev.dmigrate.server.core.upload.FinalizationOutcomeStatus
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.core.upload.UploadSessionTransitions
import dev.dmigrate.server.core.upload.UploadSessionTransitions.FinalizeValidation
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.PersistOutcome
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import java.time.Instant

/**
 * Application-layer orchestrator for the upload session lifecycle.
 * LF-010 / LF-013 / LN-009 / LN-011 demands that TTL/abort/expiry/finalize trigger
 * `UploadSegmentStore.deleteAllForSession(...)` — that responsibility
 * lives here, not on the stores themselves. `finalize` additionally
 * runs the LF-010 / LF-013 / LN-009 / LN-011 finalize invariants and verifies that the artifact
 * has actually been published into the [ArtifactContentStore] before
 * the session transitions to COMPLETED.
 */
class UploadSessionService(
    private val sessions: UploadSessionStore,
    private val segments: UploadSegmentStore,
    private val artifacts: ArtifactContentStore,
    /**
     * LF-010 / LF-013 / LN-009 / LN-011: optionaler [QuotaService] fuer den
     * Expiry-Sweeper. Wenn gewired, gibt der Service auf TTL-/Idle-
     * Expiry die Init-Quotas (`ACTIVE_UPLOAD_SESSIONS=1`,
     * `UPLOAD_BYTES=session.sizeBytes`) frei, sodass ein Tenant
     * nach Idle-Timeout nicht in den Limits gebunden bleibt.
     * Default `null` haelt Bestands-Tests gruen — Production-Wiring
     * (CLI-Sweeper, server-state bootstrap) reicht den Service durch.
     */
    private val quotaService: QuotaService? = null,
) {

    fun expireDue(now: Instant): List<UploadSession> {
        val expired = sessions.expireDue(now)
        for (session in expired) {
            segments.deleteAllForSession(session.uploadSessionId)
            // LF-010 / LF-013 / LN-009 / LN-011: "Quota-Release fuer Abort, Expiry und
            // fehlgeschlagene Finalisierung idempotent ausfuehren".
            // Der QuotaService.release ist idempotent (no-op bei
            // nicht-positivem aktuellem Counter); doppelter Sweep
            // dieselbe Session ist sicher.
            releaseInitQuotas(session)
        }
        return expired
    }

    private fun releaseInitQuotas(session: UploadSession) {
        val service = quotaService ?: return
        service.release(
            QuotaReservation(
                key = QuotaKey(
                    session.tenantId,
                    QuotaDimension.ACTIVE_UPLOAD_SESSIONS,
                    session.ownerPrincipalId,
                ),
                amount = 1,
            ),
        )
        service.release(
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

    /**
     * LF-010 / LF-013 / LN-009 / LN-011— Sweeper-Hook fuer Upload-
     * Finalisierungs-Timeouts.
     *
     * Findet alle `FINALIZING`-Sessions, deren `finalizingLeaseExpiresAt`
     * vor [now] liegt, und bringt sie deterministisch in den durablen
     * Failure-State:
     *
     * 1. Persistiert einen `FinalizationOutcome` mit
     *    `status = FAILED` und `sanitizedErrorCode = "OPERATION_TIMEOUT"`
     *    (claim-keyed CAS gegen den noch aktiven `finalizingClaimId`).
     * 2. Transitioniert die Session auf `ABORTED` (Plan-Wortlaut
     *    "Session FAILED" — der durable State ist `ABORTED` mit
     *    `FailureOutcome.status=FAILED`, analog zu LF-010 / LF-013 / LN-009 / LN-011 (3/3) und
     *    LF-010 / LF-013 / LN-009 / LN-011 (1/3)).
     * 3. Loescht Zwischensegmente (Cleanup/Tombstone).
     * 4. Gibt Init-Quotas frei (`ACTIVE_UPLOAD_SESSIONS` +
     *    `UPLOAD_BYTES`).
     *
     * Idempotent: ein zweiter Sweep mit identischem [now] findet die
     * Session bereits als ABORTED und ueberspringt sie.
     *
     * @return Anzahl der durabel zu Timeout gebrachten Sessions.
     */
    fun timeoutStaleFinalizingSessions(now: Instant): Int {
        val stale = sessions.findStaleFinalizing(now)
        var timedOut = 0
        for (session in stale) {
            val claimId = session.finalizingClaimId ?: continue
            val outcome = FinalizationOutcome(
                claimId = claimId,
                payloadSha256 = "",
                artifactId = "",
                schemaId = null,
                format = "",
                status = FinalizationOutcomeStatus.FAILED,
                sanitizedErrorCode = "OPERATION_TIMEOUT",
                sanitizedErrorMessage = "finalisation lease expired",
            )
            val persisted = sessions.persistFinalizationOutcome(
                tenantId = session.tenantId,
                uploadSessionId = session.uploadSessionId,
                claimId = claimId,
                outcome = outcome,
                now = now,
            )
            if (persisted !is PersistOutcome.Persisted) continue
            sessions.transition(session.tenantId, session.uploadSessionId, UploadSessionState.ABORTED, now)
            segments.deleteAllForSession(session.uploadSessionId)
            releaseInitQuotas(session)
            timedOut++
        }
        return timedOut
    }

    fun abort(tenantId: TenantId, uploadSessionId: String, now: Instant): TransitionOutcome {
        val outcome = sessions.transition(
            tenantId = tenantId,
            uploadSessionId = uploadSessionId,
            newState = UploadSessionState.ABORTED,
            now = now,
        )
        if (outcome is TransitionOutcome.Applied) {
            segments.deleteAllForSession(uploadSessionId)
        }
        return outcome
    }

    /**
     * Validates the finalize invariants, requires the materialized
     * artifact to be present in the [ArtifactContentStore], then
     * transitions the session to COMPLETED and clears the spool. The
     * COMPLETED-transition is therefore *gated on the artifact already
     * being published* — the kernel itself enforces "successful publish
     * before COMPLETED", so callers cannot accidentally drop the spool
     * before the artifact reaches its store.
     *
     * Returns:
     *   - `Applied(session)` on success
     *   - `ValidationFailed(reason)` if [UploadSessionTransitions.validateFinalize]
     *     reports any of the §6.3 invariants violated
     *   - `ArtifactNotMaterialized(artifactId)` if `artifacts.exists` is
     *     false — caller must publish the artifact first and retry
     *   - `IllegalTransition` / `NotFound` from the session-store
     *     transition step
     */
    fun finalize(
        tenantId: TenantId,
        uploadSessionId: String,
        artifactId: String,
        actualTotalChecksum: String,
        now: Instant,
    ): FinalizeOutcome {
        val session = sessions.findById(tenantId, uploadSessionId)
            ?: return FinalizeOutcome.NotFound
        val validation = UploadSessionTransitions.validateFinalize(
            session = session,
            segments = segments.listSegments(uploadSessionId),
            actualTotalChecksum = actualTotalChecksum,
        )
        if (validation !is FinalizeValidation.Ok) {
            return FinalizeOutcome.ValidationFailed(validation)
        }
        if (!artifacts.exists(artifactId)) {
            return FinalizeOutcome.ArtifactNotMaterialized(artifactId)
        }
        val transition = sessions.transition(
            tenantId = tenantId,
            uploadSessionId = uploadSessionId,
            newState = UploadSessionState.COMPLETED,
            now = now,
        )
        return when (transition) {
            is TransitionOutcome.Applied -> {
                segments.deleteAllForSession(uploadSessionId)
                FinalizeOutcome.Applied(transition.session)
            }
            is TransitionOutcome.IllegalTransition ->
                FinalizeOutcome.IllegalTransition(transition.from, transition.to)
            TransitionOutcome.NotFound -> FinalizeOutcome.NotFound
        }
    }

    sealed interface FinalizeOutcome {
        data class Applied(val session: UploadSession) : FinalizeOutcome
        data class ValidationFailed(val reason: FinalizeValidation) : FinalizeOutcome
        data class ArtifactNotMaterialized(val artifactId: String) : FinalizeOutcome
        data class IllegalTransition(
            val from: UploadSessionState,
            val to: UploadSessionState,
        ) : FinalizeOutcome
        data object NotFound : FinalizeOutcome
    }
}
