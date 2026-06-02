package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.job.JobCancelService
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JobCancelHandlerTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val now: Instant = Fixtures.NOW
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    class Fixture {
        val store = InMemoryJobStore()
        val registry = InMemoryWorkerHandleRegistry()
        val service = JobCancelService(jobStore = store, workerHandleRegistry = registry, cancelReasonScrubber = { it })
        val handler = JobCancelHandler(service, clock)

        fun seedJob(
            jobId: String = "j1",
            owner: String = "alice",
            status: JobStatus = JobStatus.QUEUED,
            tenantName: String = "acme",
            visibility: JobVisibility = JobVisibility.OWNER,
        ) = store.save(
            Fixtures.jobRecord(jobId, tenant = tenantName, owner = owner, status = status, visibility = visibility),
        )
    }

    fun ctx(args: JsonObject, principal: String = "alice", tenant: String = "acme") = ToolCallContext(
        name = "job_cancel",
        arguments = args,
        principal = Fixtures.principalContext(principalId = principal, tenant = tenant),
        requestId = "req-test",
    )

    fun argsObj(jobId: String? = null, resourceUri: String? = null, reason: String? = null): JsonObject =
        JsonObject().apply {
            if (jobId != null) addProperty("jobId", jobId)
            if (resourceUri != null) addProperty("resourceUri", resourceUri)
            if (reason != null) addProperty("reason", reason)
        }

    test("Eigener QUEUED-Job: Success mit status=CANCELLED, terminal=true, executionMeta.cancelRequested=true") {
        val fx = Fixture().also { it.seedJob() }
        val result = fx.handler.handle(ctx(argsObj(jobId = "j1", reason = "no-longer-needed")))
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val payload = JsonParser.parseString(result.content.first().text!!).asJsonObject
        payload.get("jobId").asString shouldBe "j1"
        payload.get("status").asString shouldBe "CANCELLED"
        payload.get("terminal").asBoolean shouldBe true
        payload.get("resourceUri").asString shouldBe "dmigrate://tenants/acme/jobs/j1"
        val em = payload.getAsJsonObject("executionMeta")
        em.get("requestId").asString shouldBe "req-test"
        em.get("cancelRequested").asBoolean shouldBe true
        em.get("cancelRequestedReason").asString shouldBe "no-longer-needed"
    }

    test("Eigener RUNNING-Job: AckPending → status=RUNNING, terminal=false, cancelAckPending=true, retryAfter") {
        val fx = Fixture().also {
            it.seedJob(status = JobStatus.RUNNING)
            it.registry.register("j1", CancellationTokenSource.create())
        }
        val result = fx.handler.handle(ctx(argsObj(jobId = "j1", reason = "user")))
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val payload = JsonParser.parseString(result.content.first().text!!).asJsonObject
        payload.get("status").asString shouldBe "RUNNING"
        payload.get("terminal").asBoolean shouldBe false
        val em = payload.getAsJsonObject("executionMeta")
        em.get("cancelRequested").asBoolean shouldBe true
        em.get("cancelAckPending").asBoolean shouldBe true
        em.get("retryAfter").asLong shouldBe 2L
    }

    test("Terminaler Job (SUCCEEDED): Success mit aktuellen Werten, kein cancelRequested") {
        val fx = Fixture().also { it.seedJob(status = JobStatus.SUCCEEDED) }
        val result = fx.handler.handle(ctx(argsObj(jobId = "j1")))
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val payload = JsonParser.parseString(result.content.first().text!!).asJsonObject
        payload.get("status").asString shouldBe "SUCCEEDED"
        payload.get("terminal").asBoolean shouldBe true
        payload.getAsJsonObject("executionMeta").has("cancelRequested") shouldBe false
    }

    test("Resource-URI als Eingabe wird akzeptiert") {
        val fx = Fixture().also { it.seedJob() }
        val result = fx.handler.handle(
            ctx(argsObj(resourceUri = "dmigrate://tenants/acme/jobs/j1")),
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val payload = JsonParser.parseString(result.content.first().text!!).asJsonObject
        payload.get("status").asString shouldBe "CANCELLED"
    }

    test("Beide jobId UND resourceUri → ValidationErrorException") {
        val fx = Fixture().also { it.seedJob() }
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(argsObj(jobId = "j1", resourceUri = "dmigrate://tenants/acme/jobs/j1")),
            )
        }
    }

    test("Weder jobId NOCH resourceUri → ValidationErrorException") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(argsObj()))
        }
    }

    test("Unbekannter jobId → RESOURCE_NOT_FOUND-Envelope (no-oracle, kein resourceUri-Echo)") {
        val fx = Fixture()
        val result = fx.handler.handle(ctx(argsObj(jobId = "j-missing")))
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.RESOURCE_NOT_FOUND
        // No-oracle: keine details. LF-017 / LF-024 / LN-030 / LN-031 line 661-662.
        result.envelope.details shouldBe emptyList()
    }

    test("Cross-tenant resourceUri → TENANT_SCOPE_DENIED-Envelope mit targetTenant") {
        val fx = Fixture().also {
            it.seedJob(tenantName = "initech", jobId = "j-other")
        }
        val result = fx.handler.handle(
            ctx(argsObj(resourceUri = "dmigrate://tenants/initech/jobs/j-other")),
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.TENANT_SCOPE_DENIED
        val keyed = result.envelope.details.associate { it.key to it.value }
        keyed["targetTenant"] shouldBe "initech"
    }

    test("Same-tenant fremder Principal ohne Admin → FORBIDDEN_PRINCIPAL") {
        val fx = Fixture().also { it.seedJob(owner = "bob") }
        val result = fx.handler.handle(ctx(argsObj(jobId = "j1"), principal = "alice"))
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.FORBIDDEN_PRINCIPAL
    }

    test("executionMeta.requestId wird aus Context uebernommen") {
        val fx = Fixture().also { it.seedJob() }
        val custom = ToolCallContext(
            name = "job_cancel",
            arguments = argsObj(jobId = "j1"),
            principal = Fixtures.principalContext(principalId = "alice", tenant = "acme"),
            requestId = "custom-req-42",
        )
        val result = fx.handler.handle(custom) as ToolCallOutcome.Success
        result.content.first().text!! shouldContain "\"requestId\":\"custom-req-42\""
    }
})
