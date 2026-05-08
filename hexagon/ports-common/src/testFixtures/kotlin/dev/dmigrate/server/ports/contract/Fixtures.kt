package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.UploadSegment
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import java.time.Instant

object Fixtures {

    val NOW: Instant = Instant.parse("2026-04-25T10:00:00Z")
    val EXPIRY: Instant = Instant.parse("2030-01-01T00:00:00Z")

    fun tenant(id: String) = TenantId(id)
    fun principal(id: String) = PrincipalId(id)

    fun quotaKey(
        dimension: QuotaDimension = QuotaDimension.ACTIVE_JOBS,
        tenant: String = "acme",
        principal: String? = null,
    ) = QuotaKey(
        tenantId = tenant(tenant),
        dimension = dimension,
        principalId = principal?.let { principal(it) },
    )

    fun principalContext(
        principalId: String = "alice",
        tenant: String = "acme",
        admin: Boolean = false,
    ) = PrincipalContext(
        principalId = principal(principalId),
        homeTenantId = tenant(tenant),
        effectiveTenantId = tenant(tenant),
        allowedTenantIds = setOf(tenant(tenant)),
        isAdmin = admin,
        auditSubject = principalId,
        authSource = AuthSource.OIDC,
        expiresAt = EXPIRY,
    )

    fun jobUri(t: String, id: String) =
        ServerResourceUri(tenant(t), ResourceKind.JOBS, id)

    fun artifactUri(t: String, id: String) =
        ServerResourceUri(tenant(t), ResourceKind.ARTIFACTS, id)

    fun jobRecord(
        jobId: String,
        tenant: String = "acme",
        owner: String = "alice",
        status: JobStatus = JobStatus.RUNNING,
        visibility: JobVisibility = JobVisibility.OWNER,
        createdAt: Instant = NOW,
        expiresAt: Instant = EXPIRY,
    ) = JobRecord(
        managedJob = ManagedJob(
            jobId = jobId,
            operation = "data.export",
            status = status,
            createdAt = createdAt,
            updatedAt = createdAt,
            expiresAt = expiresAt,
            createdBy = owner,
        ),
        tenantId = tenant(tenant),
        ownerPrincipalId = principal(owner),
        visibility = visibility,
        resourceUri = jobUri(tenant, jobId),
    )

    fun artifactRecord(
        artifactId: String,
        tenant: String = "acme",
        owner: String = "alice",
        kind: ArtifactKind = ArtifactKind.SCHEMA,
        visibility: JobVisibility = JobVisibility.OWNER,
        createdAt: Instant = NOW,
        expiresAt: Instant = EXPIRY,
    ) = ArtifactRecord(
        managedArtifact = ManagedArtifact(
            artifactId = artifactId,
            filename = "$artifactId.bin",
            contentType = "application/octet-stream",
            sizeBytes = 100L,
            sha256 = "abc",
            createdAt = createdAt,
            expiresAt = expiresAt,
        ),
        kind = kind,
        tenantId = tenant(tenant),
        ownerPrincipalId = principal(owner),
        visibility = visibility,
        resourceUri = artifactUri(tenant, artifactId),
    )

    fun uploadSession(
        sessionId: String,
        tenant: String = "acme",
        owner: String = "alice",
        state: UploadSessionState = UploadSessionState.ACTIVE,
        sizeBytes: Long = 3_000L,
        segmentTotal: Int = 3,
        idleTimeoutAt: Instant = NOW.plusSeconds(900),
        absoluteLeaseExpiresAt: Instant = NOW.plusSeconds(3600),
    ) = UploadSession(
        uploadSessionId = sessionId,
        tenantId = tenant(tenant),
        ownerPrincipalId = principal(owner),
        resourceUri = ServerResourceUri(tenant(tenant), ResourceKind.UPLOAD_SESSIONS, sessionId),
        artifactKind = ArtifactKind.SCHEMA,
        mimeType = "application/octet-stream",
        sizeBytes = sizeBytes,
        segmentTotal = segmentTotal,
        checksumSha256 = "totalhash",
        uploadIntent = "schema_staging",
        state = state,
        createdAt = NOW,
        updatedAt = NOW,
        idleTimeoutAt = idleTimeoutAt,
        absoluteLeaseExpiresAt = absoluteLeaseExpiresAt,
    )

    fun uploadSegment(
        sessionId: String,
        index: Int,
        sizeBytes: Long = 1_000L,
        hash: String = "hash$index",
    ) = UploadSegment(
        uploadSessionId = sessionId,
        segmentIndex = index,
        segmentOffset = index * sizeBytes,
        sizeBytes = sizeBytes,
        segmentSha256 = hash,
    )

    fun connectionRef(
        connectionId: String,
        tenant: String = "acme",
        sensitivity: ConnectionSensitivity = ConnectionSensitivity.PRODUCTION,
        allowedPrincipals: Set<PrincipalId>? = null,
    ) = ConnectionReference(
        connectionId = connectionId,
        tenantId = tenant(tenant),
        displayName = "conn $connectionId",
        dialectId = "postgresql",
        sensitivity = sensitivity,
        resourceUri = ServerResourceUri(tenant(tenant), ResourceKind.CONNECTIONS, connectionId),
        credentialRef = "vault:$tenant/$connectionId",
        allowedPrincipalIds = allowedPrincipals,
    )
}
