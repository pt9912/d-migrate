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
import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.memory.InMemoryArtifactStore
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
import dev.dmigrate.server.ports.memory.InMemoryDiffStore
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryProfileStore
import dev.dmigrate.server.ports.memory.InMemorySchemaStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant

private val TENANT = TenantId("acme")
private val OTHER_TENANT = TenantId("globex")
private val ALICE = PrincipalId("alice")
private val BOB = PrincipalId("bob")
private val NOW = Instant.parse("2026-05-01T00:00:00Z")
private val LATER = NOW.plusSeconds(86400)

private fun principal(
    id: PrincipalId,
    tenantId: TenantId,
    isAdmin: Boolean = false,
): PrincipalContext = PrincipalContext(
    principalId = id,
    homeTenantId = tenantId,
    effectiveTenantId = tenantId,
    allowedTenantIds = setOf(tenantId),
    scopes = setOf("dmigrate:read"),
    isAdmin = isAdmin,
    auditSubject = id.value,
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)

private fun job(jobId: String, tenant: TenantId, owner: PrincipalId, visibility: JobVisibility): JobRecord = JobRecord(
    managedJob = ManagedJob(
        jobId = jobId,
        operation = "schema_reverse",
        status = JobStatus.RUNNING,
        createdAt = NOW,
        updatedAt = NOW,
        expiresAt = LATER,
        createdBy = owner.value,
    ),
    tenantId = tenant,
    ownerPrincipalId = owner,
    visibility = visibility,
    resourceUri = ServerResourceUri(tenant, ResourceKind.JOBS, jobId),
)

private fun artifact(id: String, tenant: TenantId, owner: PrincipalId, visibility: JobVisibility): ArtifactRecord =
    ArtifactRecord(
        managedArtifact = ManagedArtifact(
            artifactId = id,
            filename = "$id.json",
            contentType = "application/json",
            sizeBytes = 100,
            sha256 = "deadbeef",
            createdAt = NOW,
            expiresAt = LATER,
        ),
        kind = ArtifactKind.SCHEMA,
        tenantId = tenant,
        ownerPrincipalId = owner,
        visibility = visibility,
        resourceUri = ServerResourceUri(tenant, ResourceKind.ARTIFACTS, id),
    )

private fun seededStores(): ResourceStores {
    val jobStore = InMemoryJobStore().apply {
        save(job("job-1", TENANT, ALICE, JobVisibility.OWNER))
        save(job("job-2", TENANT, BOB, JobVisibility.OWNER))
        save(job("job-3", TENANT, ALICE, JobVisibility.TENANT))
        save(job("job-other", OTHER_TENANT, ALICE, JobVisibility.OWNER))
    }
    val artifactStore = InMemoryArtifactStore().apply {
        save(artifact("art-1", TENANT, ALICE, JobVisibility.OWNER))
        save(artifact("art-2", TENANT, BOB, JobVisibility.TENANT))
    }
    val schemaStore = InMemorySchemaStore().apply {
        save(
            SchemaIndexEntry(
                schemaId = "s1",
                tenantId = TENANT,
                resourceUri = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, "s1"),
                artifactRef = "art-1",
                displayName = "schema-1",
                createdAt = NOW,
                expiresAt = LATER,
            ),
        )
    }
    val profileStore = InMemoryProfileStore().apply {
        save(
            ProfileIndexEntry(
                profileId = "p1",
                tenantId = TENANT,
                resourceUri = ServerResourceUri(TENANT, ResourceKind.PROFILES, "p1"),
                artifactRef = "art-x",
                displayName = "profile-1",
                createdAt = NOW,
                expiresAt = LATER,
            ),
        )
    }
    val diffStore = InMemoryDiffStore().apply {
        save(
            DiffIndexEntry(
                diffId = "d1",
                tenantId = TENANT,
                resourceUri = ServerResourceUri(TENANT, ResourceKind.DIFFS, "d1"),
                artifactRef = "art-y",
                sourceRef = "src",
                targetRef = "tgt",
                displayName = "diff-1",
                createdAt = NOW,
                expiresAt = LATER,
            ),
        )
    }
    val connectionStore = InMemoryConnectionReferenceStore().apply {
        save(
            ConnectionReference(
                connectionId = "conn-1",
                tenantId = TENANT,
                displayName = "Local DB",
                dialectId = "postgresql",
                sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                resourceUri = ServerResourceUri(TENANT, ResourceKind.CONNECTIONS, "conn-1"),
            ),
        )
    }
    return ResourceStores(
        jobStore = jobStore,
        artifactStore = artifactStore,
        schemaStore = schemaStore,
        profileStore = profileStore,
        diffStore = diffStore,
        connectionStore = connectionStore,
    )
}

class ResourcesListHandlerTest : FunSpec({

    test("empty stores produce empty list and null cursor") {
        val handler = ResourcesListHandler(ResourceStores.empty())
        val result = handler.list(principal(ALICE, TENANT), cursor = null)
        result.resources shouldBe emptyList()
        result.nextCursor shouldBe null
    }

    test("walks every resource family and projects principal-readable items") {
        val handler = ResourcesListHandler(seededStores(), defaultPageSize = 100)
        val result = handler.list(principal(ALICE, TENANT), cursor = null)
        val uris = result.resources.map { it.uri }
        uris shouldContain "dmigrate://tenants/acme/jobs/job-1"
        uris shouldContain "dmigrate://tenants/acme/jobs/job-3"
        uris shouldContain "dmigrate://tenants/acme/artifacts/art-1"
        uris shouldContain "dmigrate://tenants/acme/artifacts/art-2"
        uris shouldContain "dmigrate://tenants/acme/schemas/s1"
        uris shouldContain "dmigrate://tenants/acme/profiles/p1"
        uris shouldContain "dmigrate://tenants/acme/diffs/d1"
        uris shouldContain "dmigrate://tenants/acme/connections/conn-1"
        result.nextCursor shouldBe null
    }

    test("OWNER-visibility filter excludes other principals' jobs") {
        val handler = ResourcesListHandler(seededStores(), defaultPageSize = 100)
        val result = handler.list(principal(ALICE, TENANT), cursor = null)
        val jobUris = result.resources.map { it.uri }.filter { it.contains("/jobs/") }
        // job-1: alice/OWNER → readable
        // job-2: bob/OWNER → NOT readable for alice
        // job-3: alice/TENANT → readable
        jobUris shouldContain "dmigrate://tenants/acme/jobs/job-1"
        jobUris shouldContain "dmigrate://tenants/acme/jobs/job-3"
        (jobUris.contains("dmigrate://tenants/acme/jobs/job-2")) shouldBe false
    }

    test("foreign-tenant jobs are not visible (tenant scoping)") {
        val handler = ResourcesListHandler(seededStores(), defaultPageSize = 100)
        val result = handler.list(principal(ALICE, TENANT), cursor = null)
        result.resources.none { it.uri.contains("job-other") } shouldBe true
    }

    test("pagination boundary: pageSize=1 returns one resource AND a cursor") {
        val handler = ResourcesListHandler(seededStores(), defaultPageSize = 1)
        val result = handler.list(principal(ALICE, TENANT), cursor = null, pageSize = 1)
        result.resources.size shouldBe 1
        // With more readable items in the JobStore, the handler MUST
        // return a non-null nextCursor so the client knows to resume.
        result.nextCursor shouldNotBe null
    }

    test("walking with the returned cursor eventually drains everything") {
        val handler = ResourcesListHandler(seededStores(), defaultPageSize = 1)
        val visited = mutableListOf<String>()
        var cursor: ResourcesListCursor? = null
        var iterations = 0
        while (iterations < 20) {
            val result = handler.list(principal(ALICE, TENANT), cursor = cursor, pageSize = 1)
            visited += result.resources.map { it.uri }
            val raw = result.nextCursor
            if (raw == null) break // walk complete — no more pages
            cursor = ResourcesListCursor.decode(raw)
            iterations++
        }
        // Alice sees: job-1, job-3, art-1, art-2, schema, profile, diff, connection = 8 items.
        visited.size shouldBe 8
    }

    test("rejects pageSize <= 0") {
        val handler = ResourcesListHandler(ResourceStores.empty())
        val ex = io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            handler.list(principal(ALICE, TENANT), cursor = null, pageSize = 0)
        }
        ex.message!! shouldBe "pageSize must be > 0 (got 0)"
    }
})
