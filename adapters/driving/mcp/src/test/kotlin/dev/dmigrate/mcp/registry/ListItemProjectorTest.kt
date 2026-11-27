package dev.dmigrate.mcp.registry

import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

private val TENANT = TenantId("acme")

private fun job(artifacts: List<String>): JobRecord {
    val now = Instant.parse("2026-05-02T12:00:00Z")
    return JobRecord(
        managedJob = ManagedJob(
            jobId = "job-1",
            operation = "schema.reverse",
            status = JobStatus.SUCCEEDED,
            createdAt = now,
            updatedAt = now,
            expiresAt = now.plusSeconds(3600),
            createdBy = "alice",
            artifacts = artifacts,
        ),
        tenantId = TENANT,
        ownerPrincipalId = PrincipalId("alice"),
        visibility = JobVisibility.OWNER,
        resourceUri = ServerResourceUri(TENANT, ResourceKind.JOBS, "job-1"),
    )
}

class ListItemProjectorTest : FunSpec({

    // Die Lese-Jobs speichern vollstaendige URIs; job_list setzte das Praefix noch einmal davor (Issue #86).
    test("job_list nennt jedes Artefakt als eine URI, ob als URI oder als nackte Kennung gespeichert") {
        val item = ListItemProjector.projectJobListItem(
            job(listOf("dmigrate://tenants/acme/artifacts/art-1", "art-2")),
        )

        item["artifactUris"] shouldBe listOf(
            "dmigrate://tenants/acme/artifacts/art-1",
            "dmigrate://tenants/acme/artifacts/art-2",
        )
    }
})
