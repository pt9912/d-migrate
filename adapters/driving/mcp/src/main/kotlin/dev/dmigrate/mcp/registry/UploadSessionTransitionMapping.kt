package dev.dmigrate.mcp.registry

import dev.dmigrate.server.application.error.IdempotencyConflictException
import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.UploadSessionAbortedException
import dev.dmigrate.server.application.error.UploadSessionExpiredException
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSessionStore
import java.time.Instant

/**
 * Shared `transition → typed lifecycle exception` mapping for the
 * upload-session handlers (AP 6.8 `artifact_upload`, AP 6.10
 * `artifact_upload_abort`, and any future expiry sweeper). Surfaces
 * race outcomes (the session changed state between the handler's
 * pre-check and the store transition) as the matching typed
 * exception so the client never sees an opaque internal error.
 *
 * Maps:
 * - `Applied` → returns the new session
 * - `IllegalTransition(from=ABORTED)` → `UploadSessionAbortedException`
 * - `IllegalTransition(from=EXPIRED)` → `UploadSessionExpiredException`
 * - `IllegalTransition(from=COMPLETED)` → `IdempotencyConflictException`
 * - `IllegalTransition(from=ACTIVE)` → `InternalAgentErrorException`
 *   (the store contract allows ACTIVE → COMPLETED/ABORTED/EXPIRED;
 *   an `IllegalTransition.from=ACTIVE` means the store contract is
 *   itself broken)
 * - `NotFound` → `ResourceNotFoundException`
 */
internal fun UploadSessionStore.transitionOrThrow(
    session: UploadSession,
    newState: UploadSessionState,
    now: Instant,
): UploadSession =
    when (val transition = transition(session.tenantId, session.uploadSessionId, newState, now)) {
        is TransitionOutcome.Applied -> transition.session
        is TransitionOutcome.IllegalTransition -> throw when (transition.from) {
            UploadSessionState.ABORTED -> UploadSessionAbortedException(session.uploadSessionId)
            UploadSessionState.EXPIRED -> UploadSessionExpiredException(session.uploadSessionId)
            UploadSessionState.COMPLETED -> IdempotencyConflictException(
                existingFingerprint = "session=${session.uploadSessionId},state=COMPLETED",
            )
            UploadSessionState.ACTIVE -> InternalAgentErrorException()
        }
        is TransitionOutcome.NotFound -> throw ResourceNotFoundException(session.resourceUri)
    }
