package dev.dmigrate.server.application.upload

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.core.upload.UploadSessionTransitions
import dev.dmigrate.server.core.upload.UploadSessionTransitions.FinalizeValidation
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import java.time.Instant

/**
 * Application-layer orchestrator for the upload session lifecycle.
 * The plan §6.3 demands that TTL/abort/expiry/finalize trigger
 * `UploadSegmentStore.deleteAllForSession(...)` — that responsibility
 * lives here, not on the stores themselves. `finalize` additionally
 * runs the §6.3 finalize invariants and verifies that the artifact
 * has actually been published into the [ArtifactContentStore] before
 * the session transitions to COMPLETED.
 */
class UploadSessionService(
    private val sessions: UploadSessionStore,
    private val segments: UploadSegmentStore,
    private val artifacts: ArtifactContentStore,
) {

    fun expireDue(now: Instant): List<UploadSession> {
        val expired = sessions.expireDue(now)
        for (session in expired) {
            segments.deleteAllForSession(session.uploadSessionId)
        }
        return expired
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
