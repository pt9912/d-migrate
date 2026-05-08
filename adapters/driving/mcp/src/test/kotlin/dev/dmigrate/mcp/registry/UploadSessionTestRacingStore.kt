package dev.dmigrate.mcp.registry

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSessionStore
import java.time.Instant

/**
 * Test helper that wraps a real [UploadSessionStore] and forces
 * `transition(...)` to return `IllegalTransition(from = illegalFrom)`.
 * Pins the typed-exception mapping in
 * [UploadSessionTransitionMapping]'s `transitionOrThrow` without
 * having to hand-roll a delegating store at every call site.
 *
 * Used by `ArtifactUploadHandlerTest`, `ArtifactUploadAbortHandlerTest`,
 * and `UploadSessionTransitionMappingTest` to exercise concurrent
 * abort / expire / finalise races.
 */
internal fun racingTransitionStore(
    forwarding: UploadSessionStore,
    illegalFrom: UploadSessionState,
): UploadSessionStore = object : UploadSessionStore by forwarding {
    override fun transition(
        tenantId: TenantId,
        uploadSessionId: String,
        newState: UploadSessionState,
        now: Instant,
    ): TransitionOutcome = TransitionOutcome.IllegalTransition(
        from = illegalFrom,
        to = newState,
    )
}

/**
 * Same shape as [racingTransitionStore] but forces
 * `transition(...)` to report `NotFound` — exercises the
 * `transitionOrThrow → ResourceNotFoundException` branch.
 */
internal fun vanishingTransitionStore(
    forwarding: UploadSessionStore,
): UploadSessionStore = object : UploadSessionStore by forwarding {
    override fun transition(
        tenantId: TenantId,
        uploadSessionId: String,
        newState: UploadSessionState,
        now: Instant,
    ): TransitionOutcome = TransitionOutcome.NotFound
}
