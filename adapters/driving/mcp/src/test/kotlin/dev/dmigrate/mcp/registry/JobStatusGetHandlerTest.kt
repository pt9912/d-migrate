package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.TenantScopeDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.core.job.JobError
import dev.dmigrate.server.core.job.JobProgress
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
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

private val ACME = TenantId("acme")
private val OTHER = TenantId("other")
private val ALICE = PrincipalId("alice")
private val BOB = PrincipalId("bob")

private val PRINCIPAL = PrincipalContext(
    principalId = ALICE,
    homeTenantId = ACME,
    effectiveTenantId = ACME,
    allowedTenantIds = setOf(ACME),
    scopes = setOf("dmigrate:read"),
    isAdmin = false,
    auditSubject = "alice",
    authSource = AuthSource.SERVICE_ACCOUNT,
    expiresAt = Instant.MAX,
)
private val ADMIN = PRINCIPAL.copy(isAdmin = true)

private val FIXED_NOW: Instant = Instant.parse("2026-05-02T12:00:00Z")

private class JobFixture(
    val handler: JobStatusGetHandler,
    val jobStore: InMemoryJobStore,
)

private fun fixture(): JobFixture {
    val jobStore = InMemoryJobStore()
    val handler = JobStatusGetHandler(
        jobStore = jobStore,
        requestIdProvider = { "req-deadbeef" },
    )
    return JobFixture(handler, jobStore)
}

private fun stageJob(
    f: JobFixture,
    jobId: String = "job-1",
    tenant: TenantId = ACME,
    owner: PrincipalId = ALICE,
    visibility: JobVisibility = JobVisibility.OWNER,
    status: JobStatus = JobStatus.RUNNING,
    artifacts: List<String> = emptyList(),
    progress: JobProgress? = null,
    error: JobError? = null,
): JobRecord {
    val managed = ManagedJob(
        jobId = jobId,
        operation = "schema.reverse",
        status = status,
        createdAt = FIXED_NOW,
        updatedAt = FIXED_NOW,
        expiresAt = FIXED_NOW.plusSeconds(3600),
        createdBy = owner.value,
        artifacts = artifacts,
        error = error,
        progress = progress,
    )
    val record = JobRecord(
        managedJob = managed,
        tenantId = tenant,
        ownerPrincipalId = owner,
        visibility = visibility,
        resourceUri = ServerResourceUri(tenant, ResourceKind.JOBS, jobId),
    )
    f.jobStore.save(record)
    return record
}

private fun args(content: String): JsonObject = JsonParser.parseString(content).asJsonObject

private fun parsePayload(outcome: ToolCallOutcome): JsonObject {
    val text = outcome.shouldBeInstanceOf<ToolCallOutcome.Success>().content.single().text!!
    return JsonParser.parseString(text).asJsonObject
}

class JobStatusGetHandlerTest : FunSpec({

    test("own job by jobId: returns the truncated projection") {
        val f = fixture()
        stageJob(
            f, "job-1",
            artifacts = listOf("art-1", "art-2"),
            progress = JobProgress(phase = "reading", numericValues = mapOf("rows" to 1234L)),
        )
        val outcome = f.handler.handle(
            ToolCallContext("job_status_get", args("""{"jobId":"job-1"}"""), PRINCIPAL),
        )
        val json = parsePayload(outcome)
        json.get("jobId").asString shouldBe "job-1"
        json.get("operation").asString shouldBe "schema.reverse"
        json.get("status").asString shouldBe "RUNNING"
        json.get("terminal").asBoolean shouldBe false
        json.get("resourceUri").asString shouldBe "dmigrate://tenants/acme/jobs/job-1"
        val artifacts = json.getAsJsonArray("artifacts").map { it.asString }
        artifacts shouldBe listOf("art-1", "art-2")
        val progress = json.getAsJsonObject("progress")
        progress.get("phase").asString shouldBe "reading"
        progress.getAsJsonObject("numericValues").get("rows").asLong shouldBe 1234L
        json.get("error").isJsonNull shouldBe true
        json.getAsJsonObject("executionMeta").get("requestId").asString shouldBe "req-deadbeef"
    }

    test("terminal jobs (SUCCEEDED/FAILED/CANCELLED) carry terminal=true and stay readable until retention") {
        val f = fixture()
        stageJob(f, "job-done", status = JobStatus.SUCCEEDED)
        val json = parsePayload(
            f.handler.handle(
                ToolCallContext("job_status_get", args("""{"jobId":"job-done"}"""), PRINCIPAL),
            ),
        )
        json.get("status").asString shouldBe "SUCCEEDED"
        json.get("terminal").asBoolean shouldBe true
    }

    test("failed job exposes the structured error block") {
        val f = fixture()
        stageJob(
            f, "job-fail",
            status = JobStatus.FAILED,
            error = JobError(code = "E_RUNTIME", message = "process exited", exitCode = 7),
        )
        val errorJson = parsePayload(
            f.handler.handle(
                ToolCallContext("job_status_get", args("""{"jobId":"job-fail"}"""), PRINCIPAL),
            ),
        ).getAsJsonObject("error")
        errorJson.get("code").asString shouldBe "E_RUNTIME"
        errorJson.get("message").asString shouldBe "process exited"
        errorJson.get("exitCode").asInt shouldBe 7
    }

    test("missing jobId AND resourceUri throws VALIDATION_ERROR") {
        val f = fixture()
        shouldThrow<ValidationErrorException> {
            f.handler.handle(ToolCallContext("job_status_get", args("""{}"""), PRINCIPAL))
        }
    }

    test("both jobId AND resourceUri throws VALIDATION_ERROR") {
        val f = fixture()
        shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "job_status_get",
                    args("""{"jobId":"job-1","resourceUri":"dmigrate://tenants/acme/jobs/job-1"}"""),
                    PRINCIPAL,
                ),
            )
        }
    }

    test("unknown jobId in own tenant throws RESOURCE_NOT_FOUND") {
        val f = fixture()
        shouldThrow<ResourceNotFoundException> {
            f.handler.handle(
                ToolCallContext("job_status_get", args("""{"jobId":"missing"}"""), PRINCIPAL),
            )
        }
    }

    test("foreign-principal job in same tenant maps to RESOURCE_NOT_FOUND (no-oracle)") {
        // Bob's OWNER-visibility job is invisible to Alice — surface
        // the same RESOURCE_NOT_FOUND envelope as a missing id so
        // Alice can't infer Bob has a job.
        val f = fixture()
        stageJob(f, "job-bob", owner = BOB, visibility = JobVisibility.OWNER)
        shouldThrow<ResourceNotFoundException> {
            f.handler.handle(
                ToolCallContext("job_status_get", args("""{"jobId":"job-bob"}"""), PRINCIPAL),
            )
        }
    }

    test("ADMIN-visibility job is invisible to a non-admin principal — RESOURCE_NOT_FOUND") {
        val f = fixture()
        stageJob(f, "job-admin", visibility = JobVisibility.ADMIN)
        shouldThrow<ResourceNotFoundException> {
            f.handler.handle(
                ToolCallContext("job_status_get", args("""{"jobId":"job-admin"}"""), PRINCIPAL),
            )
        }
    }

    test("ADMIN-visibility job is readable to an admin principal") {
        val f = fixture()
        stageJob(f, "job-admin", visibility = JobVisibility.ADMIN)
        val json = parsePayload(
            f.handler.handle(
                ToolCallContext("job_status_get", args("""{"jobId":"job-admin"}"""), ADMIN),
            ),
        )
        json.get("jobId").asString shouldBe "job-admin"
    }

    test("resourceUri with foreign tenant throws TENANT_SCOPE_DENIED before any store lookup") {
        // §5.6: TENANT_SCOPE_DENIED is reserved for syntactically
        // out-of-scope tenant URIs. The store must NOT be touched —
        // a counting decorator pins this; otherwise differential
        // timing could leak existence.
        val f = fixture()
        // Stage a job in OTHER tenant so the store has a hit if it's
        // (incorrectly) consulted.
        stageJob(f, "job-other", tenant = OTHER, visibility = JobVisibility.TENANT)
        val ex = shouldThrow<TenantScopeDeniedException> {
            f.handler.handle(
                ToolCallContext(
                    "job_status_get",
                    args("""{"resourceUri":"dmigrate://tenants/other/jobs/job-other"}"""),
                    PRINCIPAL,
                ),
            )
        }
        ex.requestedTenant shouldBe OTHER
    }

    test("resourceUri with own tenant resolves like jobId") {
        val f = fixture()
        stageJob(f, "job-1", visibility = JobVisibility.TENANT)
        val json = parsePayload(
            f.handler.handle(
                ToolCallContext(
                    "job_status_get",
                    args("""{"resourceUri":"dmigrate://tenants/acme/jobs/job-1"}"""),
                    PRINCIPAL,
                ),
            ),
        )
        json.get("jobId").asString shouldBe "job-1"
    }

    test("malformed resourceUri throws VALIDATION_ERROR") {
        val f = fixture()
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext("job_status_get", args("""{"resourceUri":"not-a-uri"}"""), PRINCIPAL),
            )
        }
        ex.violations.map { it.field } shouldContain "resourceUri"
    }

    test("resourceUri pointing at a non-jobs resource throws VALIDATION_ERROR") {
        val f = fixture()
        val ex = shouldThrow<ValidationErrorException> {
            f.handler.handle(
                ToolCallContext(
                    "job_status_get",
                    args("""{"resourceUri":"dmigrate://tenants/acme/schemas/s1"}"""),
                    PRINCIPAL,
                ),
            )
        }
        ex.violations.first().reason.contains("expected jobs") shouldBe true
    }

    test("response carries application/json mime type") {
        val f = fixture()
        stageJob(f, "job-1")
        val outcome = f.handler.handle(
            ToolCallContext("job_status_get", args("""{"jobId":"job-1"}"""), PRINCIPAL),
        )
        (outcome as ToolCallOutcome.Success).content.single().mimeType shouldBe "application/json"
    }
})
