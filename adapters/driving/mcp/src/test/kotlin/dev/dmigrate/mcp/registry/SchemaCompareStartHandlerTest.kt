package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.job.ApprovedRetryService
import dev.dmigrate.text.FakeUnicodeTextService
import dev.dmigrate.server.application.job.JobStartOrchestrator
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SchemaCompareStartHandlerTest : FunSpec({

    val clock = Clock.fixed(Instant.parse("2026-05-05T12:00:00Z"), ZoneOffset.UTC)

    class Fixture {
        val jobStore = InMemoryJobStore()
        private val idempotencyStore = InMemoryIdempotencyStore()
        private val workerHandleRegistry = InMemoryWorkerHandleRegistry()
        private val approvalGrantStore = InMemoryApprovalGrantStore()
        private val transaction = InMemoryJobStartTransaction(jobStore, idempotencyStore)
        private val grantService = DefaultApprovalGrantService(approvalGrantStore, ApprovalGrantValidator())
        private val approvedRetryService = ApprovedRetryService(
            approvalGrantService = grantService,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            jobIdFactory = { "job_1" },
        )
        private val orchestrator = JobStartOrchestrator(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            approvedRetryService = approvedRetryService,
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = PolicyEffect.Allow),
            payloadFingerprintService = DefaultPayloadFingerprintService(FakeUnicodeTextService()),
            jobIdFactory = { "job_1" },
        )
        val handler = SchemaCompareStartHandler(orchestrator, clock)
    }

    fun ctx(args: JsonObject) = ToolCallContext(
        name = SchemaCompareStartHandler.TOOL_NAME,
        arguments = args,
        principal = Fixtures.principalContext(principalId = "alice", tenant = "acme"),
        requestId = "req-compare",
    )

    test("schema_compare_start accepts connection refs as compare inputs") {
        val fx = Fixture()
        val args = JsonObject().apply {
            addProperty("sourceUri", "dmigrate://tenants/acme/connections/source")
            addProperty("targetUri", "dmigrate://tenants/acme/connections/target")
            addProperty("idempotencyKey", "idem-compare-connections")
        }

        val result = fx.handler.handle(ctx(args))

        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
        result.content.first().text!! shouldContain "\"resourceUri\":\"dmigrate://tenants/acme/jobs/job_1\""
        val record = fx.jobStore.findById(Fixtures.tenant("acme"), "job_1")!!
        record.managedJob.operation shouldBe SchemaCompareStartHandler.OPERATION
        record.ownerPrincipalId shouldBe Fixtures.principal("alice")
    }
})
