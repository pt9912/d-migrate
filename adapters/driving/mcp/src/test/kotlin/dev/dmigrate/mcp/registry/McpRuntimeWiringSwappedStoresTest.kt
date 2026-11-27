package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.core.upload.AssembledUploadPayload
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.ports.memory.InMemoryArtifactContentStore
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSegmentStore
import dev.dmigrate.server.ports.memory.InMemoryUploadSessionStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private val NOW: Instant = Instant.parse("2026-05-02T12:00:00Z")
private val TENANT = TenantId("acme")

private val PRINCIPAL = PrincipalContext(
    principalId = PrincipalId("alice"),
    homeTenantId = TENANT,
    effectiveTenantId = TENANT,
    allowedTenantIds = setOf(TENANT),
    scopes = setOf("dmigrate:artifact:upload"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = dev.dmigrate.server.core.principal.AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private val SCHEMA_YAML = """
    schema_format: "1.0"
    name: "swapped"
    version: "1.0.0"
    tables:
      users:
        columns:
          id: { type: identifier, auto_increment: true }
        primary_key: [id]
""".trimIndent().toByteArray()

private fun payload(bytes: ByteArray): AssembledUploadPayload = AssembledUploadPayload.fromBytes(
    bytes,
    sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
)

private fun session(kind: ArtifactKind, intent: String, sizeBytes: Int) = UploadSession(
    uploadSessionId = "ups-1",
    tenantId = TENANT,
    ownerPrincipalId = PRINCIPAL.principalId,
    resourceUri = ServerResourceUri(TENANT, ResourceKind.UPLOAD_SESSIONS, "ups-1"),
    artifactKind = kind,
    mimeType = "application/octet-stream",
    sizeBytes = sizeBytes.toLong(),
    segmentTotal = 1,
    checksumSha256 = "deadbeef".repeat(8),
    uploadIntent = intent,
    state = UploadSessionState.FINALIZING,
    createdAt = NOW,
    updatedAt = NOW,
    idleTimeoutAt = NOW.plusSeconds(300),
    absoluteLeaseExpiresAt = NOW.plusSeconds(3600),
)

private fun wiring() = McpRuntimeWiring(
    uploadSessionStore = InMemoryUploadSessionStore(),
    uploadSegmentStore = InMemoryUploadSegmentStore(),
    artifactStore = InMemoryArtifactStore(),
    artifactContentStore = InMemoryArtifactContentStore(),
    schemaStore = InMemorySchemaStore(),
    jobStore = InMemoryJobStore(),
    quotaService = DefaultQuotaService(InMemoryQuotaStore()) { Long.MAX_VALUE },
    limits = McpLimitsConfig(),
    clock = Clock.fixed(NOW, ZoneOffset.UTC),
)

/**
 * `mcp serve` tauscht fuer `server.state` die Stores per `copy` (Issue #85). Was die Verdrahtung aus Stores
 * selbst baut, muss danach in die neuen schreiben, nicht in die beim Bau gebundenen.
 */
class McpRuntimeWiringSwappedStoresTest : FunSpec({

    test("Schema-Upload nach copy landet in den neuen Schema- und Artefakt-Stores") {
        val original = wiring()
        val schemas = InMemorySchemaStore()
        val artifacts = InMemoryArtifactStore()
        val swapped = original.copy(schemaStore = schemas, artifactStore = artifacts)

        swapped.finalizer.complete(
            session(ArtifactKind.SCHEMA, "schema_staging_readonly", SCHEMA_YAML.size),
            PRINCIPAL,
            payload(SCHEMA_YAML),
            artifactId = "art-1",
            schemaId = "sch-1",
            format = "yaml",
        )

        artifacts.findById(TENANT, "art-1").shouldNotBeNull()
        schemas.findById(TENANT, "sch-1").shouldNotBeNull()
        original.artifactStore.findById(TENANT, "art-1").shouldBeNull()
        original.schemaStore.findById(TENANT, "sch-1").shouldBeNull()
    }

    test("job_input-Upload nach copy landet im neuen Artefakt-Store") {
        val original = wiring()
        val artifacts = InMemoryArtifactStore()
        val swapped = original.copy(artifactStore = artifacts)
        val bytes = "a,b\n1,2\n".toByteArray()

        swapped.jobInputFinalizer.complete(
            session(ArtifactKind.UPLOAD_INPUT, "job_input", bytes.size),
            PRINCIPAL,
            payload(bytes),
            artifactId = "art-2",
            format = "csv",
        )

        artifacts.findById(TENANT, "art-2").shouldNotBeNull()
        original.artifactStore.findById(TENANT, "art-2").shouldBeNull()
    }
})
