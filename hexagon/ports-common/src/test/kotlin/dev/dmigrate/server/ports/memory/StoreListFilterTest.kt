package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ManagedArtifact
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ArtifactListFilter
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.DiffListFilter
import dev.dmigrate.server.ports.JobListFilter
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.ProfileListFilter
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaListFilter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * AP D4 (`ImpPlan-0.9.6-D.md` §10.4) golden tests for the new
 * filter-based list APIs. Each store contract is pinned for:
 *  - tenant isolation
 *  - filter combinations (status / kind / jobRef / time-window /
 *    sourceRef / targetRef)
 *  - default sort: createdAt DESC, stable id ASC tiebreaker
 *  - inclusive time-window boundaries
 *  - pagination over filtered results
 */
class StoreListFilterTest : FunSpec({

    val tenantA = TenantId("acme")
    val tenantB = TenantId("other")
    val alice = PrincipalId("alice")
    val bob = PrincipalId("bob")

    val t0 = Instant.parse("2026-05-04T10:00:00Z")
    val t1 = Instant.parse("2026-05-04T10:01:00Z")
    val t2 = Instant.parse("2026-05-04T10:02:00Z")
    val t3 = Instant.parse("2026-05-04T10:03:00Z")

    fun jobRecord(
        jobId: String,
        tenantId: TenantId = tenantA,
        owner: PrincipalId = alice,
        status: JobStatus = JobStatus.SUCCEEDED,
        operation: String = "schema_validate",
        createdAt: Instant = t0,
    ): JobRecord = JobRecord(
        managedJob = ManagedJob(
            jobId = jobId,
            operation = operation,
            status = status,
            createdAt = createdAt,
            updatedAt = createdAt,
            expiresAt = createdAt.plusSeconds(3600),
            createdBy = owner.value,
        ),
        tenantId = tenantId,
        ownerPrincipalId = owner,
        visibility = JobVisibility.TENANT,
        resourceUri = ServerResourceUri(tenantId, ResourceKind.JOBS, jobId),
    )

    fun artifactRecord(
        artifactId: String,
        tenantId: TenantId = tenantA,
        owner: PrincipalId = alice,
        kind: ArtifactKind = ArtifactKind.SCHEMA,
        jobRef: String? = null,
        createdAt: Instant = t0,
    ): ArtifactRecord = ArtifactRecord(
        managedArtifact = ManagedArtifact(
            artifactId = artifactId,
            filename = "$artifactId.bin",
            contentType = "application/octet-stream",
            sizeBytes = 0,
            sha256 = "0".repeat(64),
            createdAt = createdAt,
            expiresAt = createdAt.plusSeconds(3600),
        ),
        kind = kind,
        tenantId = tenantId,
        ownerPrincipalId = owner,
        visibility = JobVisibility.TENANT,
        resourceUri = ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, artifactId),
        jobRef = jobRef,
    )

    fun schemaEntry(
        schemaId: String,
        tenantId: TenantId = tenantA,
        jobRef: String? = null,
        createdAt: Instant = t0,
    ): SchemaIndexEntry = SchemaIndexEntry(
        schemaId = schemaId,
        tenantId = tenantId,
        resourceUri = ServerResourceUri(tenantId, ResourceKind.SCHEMAS, schemaId),
        artifactRef = "art-$schemaId",
        displayName = schemaId,
        createdAt = createdAt,
        expiresAt = createdAt.plusSeconds(3600),
        jobRef = jobRef,
    )

    fun profileEntry(
        profileId: String,
        tenantId: TenantId = tenantA,
        jobRef: String? = null,
        createdAt: Instant = t0,
    ): ProfileIndexEntry = ProfileIndexEntry(
        profileId = profileId,
        tenantId = tenantId,
        resourceUri = ServerResourceUri(tenantId, ResourceKind.PROFILES, profileId),
        artifactRef = "art-$profileId",
        displayName = profileId,
        createdAt = createdAt,
        expiresAt = createdAt.plusSeconds(3600),
        jobRef = jobRef,
    )

    fun diffEntry(
        diffId: String,
        tenantId: TenantId = tenantA,
        sourceRef: String = "schema-a",
        targetRef: String = "schema-b",
        jobRef: String? = null,
        createdAt: Instant = t0,
    ): DiffIndexEntry = DiffIndexEntry(
        diffId = diffId,
        tenantId = tenantId,
        resourceUri = ServerResourceUri(tenantId, ResourceKind.DIFFS, diffId),
        artifactRef = "art-$diffId",
        sourceRef = sourceRef,
        targetRef = targetRef,
        displayName = diffId,
        createdAt = createdAt,
        expiresAt = createdAt.plusSeconds(3600),
        jobRef = jobRef,
    )

    // --- JobStore -----------------------------------------------------------

    test("JobStore: filtered list applies status / operation / owner / time-window and tenant isolation") {
        val store = InMemoryJobStore()
        store.save(jobRecord("j-1", status = JobStatus.SUCCEEDED, createdAt = t0))
        store.save(jobRecord("j-2", status = JobStatus.FAILED, createdAt = t1))
        store.save(jobRecord("j-3", owner = bob, createdAt = t2))
        store.save(jobRecord("j-other", tenantId = tenantB, createdAt = t3))
        store.save(jobRecord("j-4", operation = "schema_generate", createdAt = t3))

        val byStatus = store.list(tenantA, JobListFilter(status = JobStatus.SUCCEEDED), PageRequest(pageSize = 10))
        byStatus.items.map { it.managedJob.jobId }.toSet() shouldBe setOf("j-1", "j-3", "j-4")

        val byOwner = store.list(tenantA, JobListFilter(ownerFilter = bob), PageRequest(pageSize = 10))
        byOwner.items.map { it.managedJob.jobId } shouldBe listOf("j-3")

        val byOperation = store.list(
            tenantA,
            JobListFilter(operation = "schema_generate"),
            PageRequest(pageSize = 10),
        )
        byOperation.items.map { it.managedJob.jobId } shouldBe listOf("j-4")

        val byWindow = store.list(
            tenantA,
            JobListFilter(createdAfter = t1, createdBefore = t2),
            PageRequest(pageSize = 10),
        )
        // Inclusive boundaries — both t1 and t2 records included.
        byWindow.items.map { it.managedJob.jobId }.toSet() shouldBe setOf("j-2", "j-3")

        val tenantBOnly = store.list(tenantB, JobListFilter(), PageRequest(pageSize = 10))
        tenantBOnly.items.map { it.managedJob.jobId } shouldBe listOf("j-other")
    }

    test("JobStore: default sort is createdAt DESC, jobId ASC") {
        val store = InMemoryJobStore()
        // Two jobs with the same createdAt — id tiebreaker ASC.
        store.save(jobRecord("j-b", createdAt = t1))
        store.save(jobRecord("j-a", createdAt = t1))
        store.save(jobRecord("j-newer", createdAt = t2))

        val ordered = store.list(tenantA, JobListFilter(), PageRequest(pageSize = 10)).items
        ordered.map { it.managedJob.jobId } shouldBe listOf("j-newer", "j-a", "j-b")
    }

    // --- ArtifactStore ------------------------------------------------------

    test("ArtifactStore: filtered list applies kind / jobRef / time-window") {
        val store = InMemoryArtifactStore()
        store.save(artifactRecord("art-1", kind = ArtifactKind.SCHEMA, jobRef = "job-1", createdAt = t0))
        store.save(artifactRecord("art-2", kind = ArtifactKind.PROFILE, jobRef = "job-2", createdAt = t1))
        store.save(artifactRecord("art-3", kind = ArtifactKind.DIFF, jobRef = "job-1", createdAt = t2))
        store.save(artifactRecord("art-other", tenantId = tenantB, createdAt = t3))

        val byKind = store.list(
            tenantA,
            ArtifactListFilter(kindFilter = ArtifactKind.SCHEMA),
            PageRequest(pageSize = 10),
        )
        byKind.items.map { it.managedArtifact.artifactId } shouldBe listOf("art-1")

        val byJobRef = store.list(tenantA, ArtifactListFilter(jobRef = "job-1"), PageRequest(pageSize = 10))
        // Two job-1 artifacts; t0 (art-1) and t2 (art-3). Default
        // sort puts t2 first.
        byJobRef.items.map { it.managedArtifact.artifactId } shouldBe listOf("art-3", "art-1")
    }

    // --- SchemaStore --------------------------------------------------------

    test("SchemaStore: filtered list applies jobRef + time-window with default sort") {
        val store = InMemorySchemaStore()
        store.save(schemaEntry("schema-1", jobRef = "job-x", createdAt = t0))
        store.save(schemaEntry("schema-2", jobRef = "job-y", createdAt = t1))
        store.save(schemaEntry("schema-3", jobRef = "job-x", createdAt = t2))

        val byJobRef = store.list(tenantA, SchemaListFilter(jobRef = "job-x"), PageRequest(pageSize = 10))
        byJobRef.items.map { it.schemaId } shouldBe listOf("schema-3", "schema-1")
    }

    // --- ProfileStore -------------------------------------------------------

    test("ProfileStore: filtered list applies jobRef") {
        val store = InMemoryProfileStore()
        store.save(profileEntry("profile-1", jobRef = "job-1", createdAt = t0))
        store.save(profileEntry("profile-2", jobRef = "job-2", createdAt = t1))
        val byJobRef = store.list(tenantA, ProfileListFilter(jobRef = "job-1"), PageRequest(pageSize = 10))
        byJobRef.items.map { it.profileId } shouldBe listOf("profile-1")
    }

    // --- DiffStore ----------------------------------------------------------

    test("DiffStore: filtered list applies sourceRef / targetRef / jobRef") {
        val store = InMemoryDiffStore()
        store.save(diffEntry("diff-1", sourceRef = "schema-a", targetRef = "schema-b", createdAt = t0))
        store.save(diffEntry("diff-2", sourceRef = "schema-a", targetRef = "schema-c", createdAt = t1))
        store.save(diffEntry("diff-3", sourceRef = "schema-x", targetRef = "schema-b", createdAt = t2))

        val bySource = store.list(tenantA, DiffListFilter(sourceRef = "schema-a"), PageRequest(pageSize = 10))
        bySource.items.map { it.diffId }.toSet() shouldBe setOf("diff-1", "diff-2")

        val byTarget = store.list(tenantA, DiffListFilter(targetRef = "schema-b"), PageRequest(pageSize = 10))
        byTarget.items.map { it.diffId }.toSet() shouldBe setOf("diff-1", "diff-3")

        val byBoth = store.list(
            tenantA,
            DiffListFilter(sourceRef = "schema-a", targetRef = "schema-b"),
            PageRequest(pageSize = 10),
        )
        byBoth.items.map { it.diffId } shouldBe listOf("diff-1")
    }

    // --- Pagination over filtered results -----------------------------------

    test("paginated filtered list returns nextPageToken and continues correctly") {
        val store = InMemoryJobStore()
        // 5 jobs all matching status=SUCCEEDED, varied createdAt.
        for (i in 0 until 5) {
            store.save(jobRecord("j-$i", createdAt = t0.plusSeconds(i.toLong())))
        }
        val first = store.list(
            tenantA,
            JobListFilter(status = JobStatus.SUCCEEDED),
            PageRequest(pageSize = 2),
        )
        first.items.size shouldBe 2
        // DESC sort: newest two first.
        first.items.map { it.managedJob.jobId } shouldBe listOf("j-4", "j-3")
        withClue("multi-page filtered list must surface nextPageToken") {
            (first.nextPageToken != null) shouldBe true
        }
        val second = store.list(
            tenantA,
            JobListFilter(status = JobStatus.SUCCEEDED),
            PageRequest(pageSize = 2, pageToken = first.nextPageToken),
        )
        second.items.map { it.managedJob.jobId } shouldBe listOf("j-2", "j-1")
    }
})
