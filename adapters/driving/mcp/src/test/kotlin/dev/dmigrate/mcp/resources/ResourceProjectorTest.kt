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
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.SchemaIndexEntry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Instant

private val TENANT = TenantId("acme")
private val PRINCIPAL_ID = PrincipalId("alice")
private val NOW = Instant.parse("2026-05-01T00:00:00Z")
private val LATER = NOW.plusSeconds(3600)

class ResourceProjectorTest : FunSpec({

    test("job projection fills uri/name/mimeType and surfaces operation+status") {
        val job = JobRecord(
            managedJob = ManagedJob(
                jobId = "job-1",
                operation = "schema_reverse",
                status = JobStatus.RUNNING,
                createdAt = NOW,
                updatedAt = NOW,
                expiresAt = LATER,
                createdBy = "alice",
            ),
            tenantId = TENANT,
            ownerPrincipalId = PRINCIPAL_ID,
            visibility = JobVisibility.OWNER,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.JOBS, "job-1"),
        )
        val resource = ResourceProjector.project(job)
        resource.uri shouldBe "dmigrate://tenants/acme/jobs/job-1"
        resource.name shouldBe "job-1"
        resource.mimeType shouldBe "application/json"
        resource.description!! shouldContain "schema_reverse"
        resource.description!! shouldContain "RUNNING"
    }

    test("artifact projection fills filename and surfaces kind+size") {
        val artifact = ArtifactRecord(
            managedArtifact = ManagedArtifact(
                artifactId = "art-1",
                filename = "schema.json",
                contentType = "application/json",
                sizeBytes = 1234,
                sha256 = "deadbeef",
                createdAt = NOW,
                expiresAt = LATER,
            ),
            kind = ArtifactKind.SCHEMA,
            tenantId = TENANT,
            ownerPrincipalId = PRINCIPAL_ID,
            visibility = JobVisibility.TENANT,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.ARTIFACTS, "art-1"),
        )
        val resource = ResourceProjector.project(artifact)
        resource.uri shouldBe "dmigrate://tenants/acme/artifacts/art-1"
        resource.name shouldBe "schema.json"
        resource.description!! shouldContain "SCHEMA"
        resource.description!! shouldContain "1234"
    }

    test("schema/profile/diff projections use displayName as name") {
        val schemaUri = ServerResourceUri(TENANT, ResourceKind.SCHEMAS, "s1")
        val schema = SchemaIndexEntry(
            schemaId = "s1",
            tenantId = TENANT,
            resourceUri = schemaUri,
            artifactRef = "art-x",
            displayName = "My Schema",
            createdAt = NOW,
            expiresAt = LATER,
        )
        ResourceProjector.project(schema).name shouldBe "My Schema"

        val profile = ProfileIndexEntry(
            profileId = "p1",
            tenantId = TENANT,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.PROFILES, "p1"),
            artifactRef = "art-y",
            displayName = "My Profile",
            createdAt = NOW,
            expiresAt = LATER,
        )
        ResourceProjector.project(profile).name shouldBe "My Profile"

        val diff = DiffIndexEntry(
            diffId = "d1",
            tenantId = TENANT,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.DIFFS, "d1"),
            artifactRef = "art-z",
            sourceRef = "src",
            targetRef = "tgt",
            displayName = "My Diff",
            createdAt = NOW,
            expiresAt = LATER,
        )
        ResourceProjector.project(diff).name shouldBe "My Diff"
    }

    test("connection projection never leaks credentialRef or providerRef") {
        val connection = ConnectionReference(
            connectionId = "conn-1",
            tenantId = TENANT,
            displayName = "Production DB",
            dialectId = "postgresql",
            sensitivity = ConnectionSensitivity.SENSITIVE,
            resourceUri = ServerResourceUri(TENANT, ResourceKind.CONNECTIONS, "conn-1"),
            credentialRef = "vault://secret/db-prod-password",
            providerRef = "vault://secret/db-provider-token",
        )
        val resource = ResourceProjector.project(connection)
        resource.uri shouldBe "dmigrate://tenants/acme/connections/conn-1"
        resource.name shouldBe "Production DB"
        // Critical: secret refs MUST NOT appear anywhere in the projection.
        val asString = resource.toString()
        asString shouldNotContain "vault://"
        asString shouldNotContain "credentialRef"
        asString shouldNotContain "providerRef"
        resource.description!! shouldContain "postgresql"
        resource.description!! shouldContain "SENSITIVE"
    }
})
