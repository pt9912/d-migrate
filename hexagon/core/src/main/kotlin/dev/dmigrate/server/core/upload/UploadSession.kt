package dev.dmigrate.server.core.upload

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Instant

enum class UploadSessionState(val terminal: Boolean) {
    ACTIVE(terminal = false),

    /**
     * AP 6.22: transient single-writer claim while a completing
     * `tools/call` runs assembly + parse + validate +
     * artefact/schema materialisation. NOT a successful terminal
     * state — only the exclusive side-effect lock for the in-flight
     * finalisation. Concurrent completing calls compete for this
     * claim via [UploadSession.finalizingClaimId] /
     * [UploadSession.finalizingLeaseExpiresAt]; the loser must not
     * start a new assembly.
     */
    FINALIZING(terminal = false),

    COMPLETED(terminal = true),
    ABORTED(terminal = true),
    EXPIRED(terminal = true),
}

data class UploadSession(
    val uploadSessionId: String,
    val tenantId: TenantId,
    val ownerPrincipalId: PrincipalId,
    val resourceUri: ServerResourceUri,
    val artifactKind: ArtifactKind,
    val mimeType: String,
    val sizeBytes: Long,
    val segmentTotal: Int,
    val checksumSha256: String,
    val uploadIntent: String,
    val state: UploadSessionState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val idleTimeoutAt: Instant,
    val absoluteLeaseExpiresAt: Instant,
    val bytesReceived: Long = 0,
    /**
     * AP 6.18: persisted finalisation outcome of the read-only
     * schema-staging session. A replay of the completing segment
     * reads this back and returns the same `schemaRef` instead of
     * surfacing `IDEMPOTENCY_CONFLICT`. `null` for any session that
     * has not produced a schemaRef yet (ACTIVE, ABORTED, EXPIRED,
     * or a COMPLETED session whose finaliser threw).
     */
    val finalisedSchemaRef: String? = null,
    /**
     * AP 6.22: opaque single-writer claim id held by the completing
     * `tools/call` that owns the in-flight finalisation. Set when
     * the session enters [UploadSessionState.FINALIZING]; cleared
     * once the session reaches a terminal state (COMPLETED /
     * ABORTED). A reclaim after lease expiry overwrites this with
     * the new claim's id. `null` for any session that has never
     * been claimed.
     */
    val finalizingClaimId: String? = null,
    /**
     * AP 6.22: wall-clock timestamp at which the current claim was
     * acquired. Diagnostic — the actual ownership decision is
     * driven by [finalizingLeaseExpiresAt].
     */
    val finalizingClaimedAt: Instant? = null,
    /**
     * AP 6.22: wall-clock cutoff after which the current claim is
     * stale and may be reclaimed by a fresh completing call. Compared
     * against the injected `Clock` of the Phase-C wiring; negative
     * clock jumps must NOT extend the stored value.
     */
    val finalizingLeaseExpiresAt: Instant? = null,
    /**
     * AP 6.22: deterministic outcome record reserved before the first
     * side effect of finalisation. Survives a crash between artefact
     * write and `COMPLETED` so the next attempt replays the same
     * artefact/schemaRef instead of producing duplicates. `null` for
     * any session that has never entered FINALIZING.
     */
    val finalizationOutcome: FinalizationOutcome? = null,
)
