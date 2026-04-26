package dev.dmigrate.server.application.upload

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.core.upload.UploadSessionTransitions
import dev.dmigrate.server.core.upload.UploadSessionTransitions.FinalizeValidation
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import java.time.Instant

/**
 * Application-layer orchestrator for the upload session lifecycle.
 * The plan §6.3 demands that TTL/abort/expiry/finalize trigger
 * `UploadSegmentStore.deleteAllForSession(...)` — that responsibility
 * lives here, not on the stores themselves. `finalize` additionally
 * runs the §6.3 finalize invariants so the COMPLETED transition is
 * gated on contiguous segments, valid offsets, matching size and
 * matching total checksum.
 */
class UploadSessionService(
    private val sessions: UploadSessionStore,
    private val segments: UploadSegmentStore,
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
     * Validates the finalize invariants before transitioning the
     * session to COMPLETED:
     * - state is ACTIVE (no double-finalize)
     * - all segments present (no gaps)
     * - each segment carries a non-blank `segmentSha256`
     * - segment offsets are contiguous starting at 0
     * - sum of segment sizes matches `session.sizeBytes`
     * - the actual aggregated checksum matches `session.checksumSha256`
     *
     * On `Ok` the session transitions to COMPLETED and the spool is
     * cleaned. Caller is responsible for having materialized the
     * segments into the [dev.dmigrate.server.ports.ArtifactContentStore]
     * before calling — the spool is dropped after this returns.
     */
    fun finalize(
        tenantId: TenantId,
        uploadSessionId: String,
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
        data class IllegalTransition(
            val from: UploadSessionState,
            val to: UploadSessionState,
        ) : FinalizeOutcome
        data object NotFound : FinalizeOutcome
    }
}
