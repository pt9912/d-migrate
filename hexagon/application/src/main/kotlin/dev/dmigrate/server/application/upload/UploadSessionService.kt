package dev.dmigrate.server.application.upload

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSegmentStore
import dev.dmigrate.server.ports.UploadSessionStore
import java.time.Instant

/**
 * Application-layer orchestrator for the upload session lifecycle.
 * The plan §6.3 demands that TTL/abort/expiry/finalize trigger
 * `UploadSegmentStore.deleteAllForSession(...)` — that responsibility
 * lives here, not on the stores themselves.
 */
class UploadSessionService(
    private val sessions: UploadSessionStore,
    private val segments: UploadSegmentStore,
) {

    /**
     * Expires every ACTIVE session whose deadline has passed and
     * deletes the spool of each. Returns the expired sessions in their
     * post-transition form.
     */
    fun expireDue(now: Instant): List<UploadSession> {
        val expired = sessions.expireDue(now)
        for (session in expired) {
            segments.deleteAllForSession(session.uploadSessionId)
        }
        return expired
    }

    /**
     * Aborts an ACTIVE session and deletes its spool. Returns the
     * transition outcome from the store; segment cleanup runs only on
     * `Applied`.
     */
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
     * Marks the session COMPLETED and deletes the spool. Caller is
     * responsible for having promoted the segments into an
     * `ArtifactContentStore` first; once that is done the per-segment
     * spool files are no longer needed.
     */
    fun finalize(tenantId: TenantId, uploadSessionId: String, now: Instant): TransitionOutcome {
        val outcome = sessions.transition(
            tenantId = tenantId,
            uploadSessionId = uploadSessionId,
            newState = UploadSessionState.COMPLETED,
            now = now,
        )
        if (outcome is TransitionOutcome.Applied) {
            segments.deleteAllForSession(uploadSessionId)
        }
        return outcome
    }
}
