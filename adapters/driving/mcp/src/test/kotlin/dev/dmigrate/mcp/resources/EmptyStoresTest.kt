package dev.dmigrate.mcp.resources

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.SchemaIndexEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * The Empty*Stores power [ResourceStores.empty] (used as the
 * bootstrap default and in the resources/list smoke tests). Beyond
 * `list`, the bootstrap never invokes save/find/delete on them, but
 * the no-op overrides are part of the interface contract — pinning
 * them with explicit assertions keeps the Kover gate honest.
 */
private val TENANT = TenantId("acme")
private val PRINCIPAL_ID = PrincipalId("alice")
private val NOW = Instant.parse("2026-05-01T00:00:00Z")
private val LATER = NOW.plusSeconds(3600)
private val PRINCIPAL = PrincipalContext(
    principalId = PRINCIPAL_ID,
    homeTenantId = TENANT,
    effectiveTenantId = TENANT,
    allowedTenantIds = setOf(TENANT),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)
private val PAGE = PageRequest(pageSize = 10)

class EmptyStoresTest : FunSpec({

    test("EmptyJobStore is a no-op for save/find/list/delete") {
        val record = JobRecord(
            managedJob = ManagedJob("j1", "op", JobStatus.QUEUED, NOW, NOW, LATER, "alice"),
            tenantId = TENANT,
            ownerPrincipalId = PRINCIPAL_ID,
            visibility = JobVisibility.OWNER,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.JOBS, "j1"),
        )
        EmptyJobStore.save(record) shouldBe record
        EmptyJobStore.findById(TENANT, "j1") shouldBe null
        EmptyJobStore.list(TENANT, PAGE).items shouldBe emptyList()
        EmptyJobStore.deleteExpired(NOW) shouldBe 0
    }

    test("EmptyArtifactStore is a no-op for save/find/list/delete") {
        val record = ArtifactRecord(
            managedArtifact = ManagedArtifact("a1", "f", "application/json", 1, "sha", NOW, LATER),
            kind = ArtifactKind.SCHEMA,
            tenantId = TENANT,
            ownerPrincipalId = PRINCIPAL_ID,
            visibility = JobVisibility.OWNER,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.ARTIFACTS, "a1"),
        )
        EmptyArtifactStore.save(record) shouldBe record
        EmptyArtifactStore.findById(TENANT, "a1") shouldBe null
        EmptyArtifactStore.list(TENANT, PAGE).items shouldBe emptyList()
        EmptyArtifactStore.deleteExpired(NOW) shouldBe 0
    }

    test("EmptySchemaStore is a no-op for save/find/list/delete") {
        val entry = SchemaIndexEntry(
            schemaId = "s1",
            tenantId = TENANT,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, "s1"),
            artifactRef = "a",
            displayName = "x",
            createdAt = NOW,
            expiresAt = LATER,
        )
        EmptySchemaStore.save(entry) shouldBe entry
        EmptySchemaStore.findById(TENANT, "s1") shouldBe null
        EmptySchemaStore.list(TENANT, PAGE).items shouldBe emptyList()
        EmptySchemaStore.deleteExpired(NOW) shouldBe 0
    }

    test("EmptyProfileStore is a no-op for save/find/list/delete") {
        val entry = ProfileIndexEntry(
            profileId = "p1",
            tenantId = TENANT,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.PROFILES, "p1"),
            artifactRef = "a",
            displayName = "x",
            createdAt = NOW,
            expiresAt = LATER,
        )
        EmptyProfileStore.save(entry) shouldBe entry
        EmptyProfileStore.findById(TENANT, "p1") shouldBe null
        EmptyProfileStore.list(TENANT, PAGE).items shouldBe emptyList()
        EmptyProfileStore.deleteExpired(NOW) shouldBe 0
    }

    test("EmptyDiffStore is a no-op for save/find/list/delete") {
        val entry = DiffIndexEntry(
            diffId = "d1",
            tenantId = TENANT,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.DIFFS, "d1"),
            artifactRef = "a",
            sourceRef = "s",
            targetRef = "t",
            displayName = "x",
            createdAt = NOW,
            expiresAt = LATER,
        )
        EmptyDiffStore.save(entry) shouldBe entry
        EmptyDiffStore.findById(TENANT, "d1") shouldBe null
        EmptyDiffStore.list(TENANT, PAGE).items shouldBe emptyList()
        EmptyDiffStore.deleteExpired(NOW) shouldBe 0
    }

    test("EmptyConnectionStore is a no-op for save/find/list/delete") {
        val ref = ConnectionReference(
            connectionId = "c1",
            tenantId = TENANT,
            displayName = "x",
            dialectId = "postgresql",
            sensitivity = ConnectionSensitivity.NON_PRODUCTION,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.CONNECTIONS, "c1"),
        )
        EmptyConnectionStore.save(ref) shouldBe ref
        EmptyConnectionStore.findById(TENANT, "c1") shouldBe null
        EmptyConnectionStore.list(PRINCIPAL, PAGE).items shouldBe emptyList()
        EmptyConnectionStore.delete(TENANT, "c1") shouldBe false
    }
})
