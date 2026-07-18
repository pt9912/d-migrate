package dev.dmigrate.mcp.registry

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.error.PolicyDeniedException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.job.ApprovedRetryService
import dev.dmigrate.text.FakeUnicodeTextService
import dev.dmigrate.server.application.job.JobStartOrchestrator
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
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
import java.util.concurrent.atomic.AtomicInteger

class SchemaReverseStartHandlerTest : FunSpec({

    val now = Instant.parse("2026-05-05T12:00:00Z")
    val clock = Clock.fixed(now, ZoneOffset.UTC)
    val gson = Gson()

    class Fixture(
        val policyDefault: PolicyEffect = PolicyEffect.Allow,
        val jobIdSeq: AtomicInteger = AtomicInteger(0),
    ) {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val workerHandleRegistry = InMemoryWorkerHandleRegistry()
        val approvalGrantStore = InMemoryApprovalGrantStore()
        val transaction = InMemoryJobStartTransaction(jobStore, idempotencyStore)
        val grantService = DefaultApprovalGrantService(approvalGrantStore, ApprovalGrantValidator())
        val approvedRetryService = ApprovedRetryService(
            approvalGrantService = grantService,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
        )
        val policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyDefault)
        val orchestrator = JobStartOrchestrator(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            approvedRetryService = approvedRetryService,
            policyService = policyService,
            payloadFingerprintService = DefaultPayloadFingerprintService(FakeUnicodeTextService()),
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
        )
        val handler = SchemaReverseStartHandler(orchestrator, clock)
    }

    fun ctx(args: JsonObject) = ToolCallContext(
        name = "schema_reverse_start",
        arguments = args,
        principal = Fixtures.principalContext(principalId = "alice", tenant = "acme"),
        requestId = "req-test",
    )

    fun args(
        connectionId: String? = "dmigrate://tenants/acme/connections/c1",
        idempotencyKey: String? = "k1",
        approvalToken: String? = null,
        includes: List<String>? = null,
    ): JsonObject = JsonObject().apply {
        if (connectionId != null) addProperty("connectionId", connectionId)
        if (idempotencyKey != null) addProperty("idempotencyKey", idempotencyKey)
        if (approvalToken != null) addProperty("approvalToken", approvalToken)
        if (includes != null) add("includes", gson.toJsonTree(includes))
    }

    test("Allowed Policy → Success-Envelope mit jobId/resourceUri/executionMeta") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(ctx(args()))
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val text = result.content.first().text!!
        text shouldContain "\"jobId\":\"job_1\""
        text shouldContain "\"resourceUri\":\"dmigrate://tenants/acme/jobs/job_1\""
        text shouldContain "\"requestId\":\"req-test\""

        // Job ist im Store mit den korrekten Bindings.
        val record = fx.jobStore.findById(Fixtures.tenant("acme"), "job_1")!!
        record.tenantId shouldBe Fixtures.tenant("acme")
        record.ownerPrincipalId shouldBe Fixtures.principal("alice")
        record.managedJob.operation shouldBe SchemaReverseStartHandler.OPERATION
    }

    test("connectionId fehlt → ValidationErrorException(connectionId)") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(connectionId = null)))
        }
        ex.violations.first().field shouldBe "connectionId"
    }

    test("idempotencyKey fehlt → ValidationErrorException, kein Job") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(idempotencyKey = null)))
        }
        ex.violations.first().field shouldBe "idempotencyKey"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("freier JDBC-URL als connectionId → ValidationErrorException, kein Store-Write") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(connectionId = "jdbc:postgresql://oops")))
        }
        fx.jobIdSeq.get() shouldBe 0
    }

    test("Tenant-Prefix-Mismatch → ValidationErrorException") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(connectionId = "dmigrate://tenants/initech/connections/c1")))
        }
    }

    test("Denied-Policy → PolicyDeniedException") {
        val fx = Fixture(policyDefault = PolicyEffect.Deny("policy:tool-blocked"))
        shouldThrow<PolicyDeniedException> {
            fx.handler.handle(ctx(args()))
        }
    }

    test("RequiresApproval ohne Token → POLICY_REQUIRED-Envelope mit Challenge") {
        val fx = Fixture(policyDefault = PolicyEffect.Challenge(setOf("schema.read")))
        val result = fx.handler.handle(ctx(args()))
        result.shouldBeInstanceOf<ToolCallOutcome.Error>()
        result.envelope.code shouldBe ToolErrorCode.POLICY_REQUIRED
        val keyed = result.envelope.details.associate { it.key to it.value }
        keyed["correlationKey"] shouldBe "k1"
        keyed["requiredScopes"] shouldBe "schema.read"
    }

    test("includes ist optional, wird vom Fingerprint mit-gehasht") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val r1 = fx.handler.handle(ctx(args(idempotencyKey = "k-fp", includes = listOf("a"))))
        r1.shouldBeInstanceOf<ToolCallOutcome.Success>()

        // Zweiter Call mit demselben idempotencyKey, aber anderen includes
        // → IDEMPOTENCY_CONFLICT (anderer Fingerprint).
        shouldThrow<dev.dmigrate.server.application.error.IdempotencyConflictException> {
            fx.handler.handle(ctx(args(idempotencyKey = "k-fp", includes = listOf("b"))))
        }
    }

    test("includes mit Nicht-String-Item → ValidationErrorException") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val invalid = JsonObject().apply {
            addProperty("connectionId", "dmigrate://tenants/acme/connections/c1")
            addProperty("idempotencyKey", "k-bad-array")
            // includes als nicht-string-array
            add("includes", gson.toJsonTree(listOf(1, 2)))
        }
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(invalid))
        }
    }
})
