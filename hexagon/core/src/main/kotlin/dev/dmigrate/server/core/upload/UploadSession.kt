package dev.dmigrate.server.core.upload

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Instant

enum class UploadSessionState(val terminal: Boolean) {
    ACTIVE(terminal = false),
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
)
