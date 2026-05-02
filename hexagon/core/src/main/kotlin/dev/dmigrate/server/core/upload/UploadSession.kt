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
     * schema-staging session. Populated by `ArtifactUploadHandler`
     * right before the ACTIVE→COMPLETED transition so a replay of
     * the completing segment can return the same `schemaRef`
     * instead of `IDEMPOTENCY_CONFLICT`. `null` while the session
     * is still ACTIVE or for terminal states that didn't produce a
     * schemaRef (ABORTED / EXPIRED / failed-validation COMPLETED).
     */
    val finalisedSchemaRef: String? = null,
)
