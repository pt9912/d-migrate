package dev.dmigrate.server.persistence.jdbc.job

import dev.dmigrate.server.core.job.JobCancelRequest
import dev.dmigrate.server.core.job.JobError
import dev.dmigrate.server.core.job.JobProgress
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
import io.kotest.matchers.string.shouldContain
import java.time.Instant

class JobRecordJsonTest : FunSpec({

    fun sample(): JobRecord = JobRecord(
        managedJob = ManagedJob(
            jobId = "job_1",
            operation = "data.export",
            status = JobStatus.RUNNING,
            createdAt = Instant.parse("2026-05-06T10:00:00Z"),
            updatedAt = Instant.parse("2026-05-06T10:05:00Z"),
            expiresAt = Instant.parse("2026-05-13T10:00:00Z"),
            createdBy = "alice",
            artifacts = listOf("a/b/c"),
            error = JobError(code = "X", message = "boom", exitCode = 7),
            progress = JobProgress(phase = "stream", numericValues = mapOf("rows" to 42L)),
            cancelRequest = JobCancelRequest(
                requested = true,
                signalAcked = true,
                requestedAt = Instant.parse("2026-05-06T10:02:00Z"),
                requestedBy = "bob",
                requestedReason = "user-cancel",
                signalSource = "mcp:job_cancel",
                ackedAt = Instant.parse("2026-05-06T10:03:00Z"),
            ),
        ),
        tenantId = TenantId("acme"),
        ownerPrincipalId = PrincipalId("alice"),
        visibility = JobVisibility.OWNER,
        resourceUri = ServerResourceUri(
            tenantId = TenantId("acme"),
            kind = ResourceKind.JOBS,
            id = "job_1",
        ),
        adminScope = "ops:read",
        quotaReservationOwnerId = "acme:alice:data.export:k1",
    )

    test("round-trip preserves all fields including nested cancelRequest/error/progress") {
        val record = sample()
        val json = JobRecordJson.toJson(record)
        val parsed = JobRecordJson.fromJson(json)
        parsed shouldBe record
    }

    test("minimal record (no error/progress/adminScope) round-trips") {
        val record = JobRecord(
            managedJob = ManagedJob(
                jobId = "job_2",
                operation = "schema.reverse",
                status = JobStatus.QUEUED,
                createdAt = Instant.parse("2026-05-06T11:00:00Z"),
                updatedAt = Instant.parse("2026-05-06T11:00:00Z"),
                expiresAt = Instant.parse("2026-05-13T11:00:00Z"),
                createdBy = "carol",
            ),
            tenantId = TenantId("umbrella"),
            ownerPrincipalId = PrincipalId("carol"),
            visibility = JobVisibility.TENANT,
            resourceUri = ServerResourceUri(
                tenantId = TenantId("umbrella"),
                kind = ResourceKind.JOBS,
                id = "job_2",
            ),
        )
        val parsed = JobRecordJson.fromJson(JobRecordJson.toJson(record))
        parsed shouldBe record
    }

    test("JSON contains tenantId/ownerPrincipalId at the JobRecord level — list-filter relevant") {
        val json = JobRecordJson.toJson(sample())
        // Plan § 6.7 list()-Pfad filtert via managed_job->>'ownerPrincipalId'.
        // Pin: das Feld liegt oben (NICHT verschachtelt im managedJob).
        json shouldContain "\"ownerPrincipalId\":\"alice\""
        json shouldContain "\"tenantId\":\"acme\""
    }
})
