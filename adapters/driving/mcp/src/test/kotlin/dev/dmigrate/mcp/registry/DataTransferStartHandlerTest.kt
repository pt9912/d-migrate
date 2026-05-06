package dev.dmigrate.mcp.registry

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.job.ApprovedRetryService
import dev.dmigrate.server.application.job.JobStartOrchestrator
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
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

/**
 * Phase F § 6.2 + § 8.8 (F.8 2/4) — Pre-Idempotency-Validation +
 * Phase-E-Pipeline-Integration des `data_transfer_start`-Handlers.
 *
 * Pin't:
 *
 * - Allow-Pfad ueber JobStartOrchestrator -> Job mit
 *   `data_transfer`-Operation in QUEUED.
 * - Pflichtfeld-Tests (idempotencyKey, sourceConnectionRef,
 *   targetConnectionRef) -> VALIDATION_ERROR.
 * - chunkSize-Range: 0 oder >10000 -> VALIDATION_ERROR.
 * - Free-JDBC-URL in beiden ConnectionRefs -> VALIDATION_ERROR.
 * - sinceColumn/since-Paar-Validierung.
 * - Blank `filter` -> VALIDATION_ERROR.
 */
class DataTransferStartHandlerTest : FunSpec({

    val now = Instant.parse("2026-05-06T12:00:00Z")
    val clock = Clock.fixed(now, ZoneOffset.UTC)
    val gson = Gson()

    class Fixture(
        policyDefault: PolicyEffect = PolicyEffect.Allow,
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
        val orchestrator = JobStartOrchestrator(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            approvalGrantStore = approvalGrantStore,
            approvedRetryService = approvedRetryService,
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyDefault),
            payloadFingerprintService = DefaultPayloadFingerprintService(),
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
        )
        val handler = DataTransferStartHandler(orchestrator, clock)
    }

    fun ctx(args: JsonObject) = ToolCallContext(
        name = "data_transfer_start",
        arguments = args,
        principal = Fixtures.principalContext(principalId = "alice", tenant = "acme"),
        requestId = "req-transfer",
    )

    fun args(
        idempotencyKey: String? = "k-transfer-1",
        sourceConnectionRef: String? = "dmigrate://tenants/acme/connections/source-db",
        targetConnectionRef: String? = "dmigrate://tenants/acme/connections/target-db",
        chunkSize: Int? = null,
        approvalToken: String? = null,
        extraFields: Map<String, Any> = emptyMap(),
    ): JsonObject = JsonObject().apply {
        if (idempotencyKey != null) addProperty("idempotencyKey", idempotencyKey)
        if (sourceConnectionRef != null) addProperty("sourceConnectionRef", sourceConnectionRef)
        if (targetConnectionRef != null) addProperty("targetConnectionRef", targetConnectionRef)
        if (chunkSize != null) addProperty("chunkSize", chunkSize)
        if (approvalToken != null) addProperty("approvalToken", approvalToken)
        for ((k, v) in extraFields) {
            add(k, gson.toJsonTree(v))
        }
    }

    test("Allow-Policy + gueltige Args -> Success mit jobId/resourceUri") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(ctx(args()))
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val text = result.content.single().text!!
        text shouldContain "\"jobId\":\"job_1\""
        text shouldContain "\"resourceUri\":\"dmigrate://tenants/acme/jobs/job_1\""

        val record = fx.jobStore.findById(Fixtures.tenant("acme"), "job_1")!!
        record.managedJob.operation shouldBe DataTransferStartHandler.OPERATION
    }

    test("idempotencyKey fehlt -> VALIDATION_ERROR, kein Job") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(idempotencyKey = null)))
        }
        ex.violations.first().field shouldBe "idempotencyKey"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("sourceConnectionRef fehlt -> VALIDATION_ERROR(sourceConnectionRef)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(sourceConnectionRef = null)))
        }
        ex.violations.first().field shouldBe "sourceConnectionRef"
    }

    test("targetConnectionRef fehlt -> VALIDATION_ERROR(targetConnectionRef)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(targetConnectionRef = null)))
        }
        ex.violations.first().field shouldBe "targetConnectionRef"
    }

    test("freier JDBC-URL als sourceConnectionRef -> VALIDATION_ERROR (kein Store-Write)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(sourceConnectionRef = "jdbc:postgresql://prod:5432/db")))
        }
        ex.violations.first().field shouldBe "sourceConnectionRef"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("freier JDBC-URL als targetConnectionRef -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(targetConnectionRef = "jdbc:mysql://localhost/db")))
        }
        fx.jobIdSeq.get() shouldBe 0
    }

    test("Cross-Tenant-Connection-Ref -> Tenant-Prefix-Mismatch -> VALIDATION_ERROR") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(args(sourceConnectionRef = "dmigrate://tenants/other/connections/source")),
            )
        }
    }

    test("chunkSize=0 -> VALIDATION_ERROR(chunkSize)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(chunkSize = 0)))
        }
        ex.violations.first().field shouldBe "chunkSize"
    }

    test("chunkSize > 10000 -> VALIDATION_ERROR(chunkSize)") {
        val fx = Fixture()
        shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(chunkSize = 10_001)))
        }
    }

    test("chunkSize=10000 (Plan-Maximum) ist zulaessig") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(ctx(args(chunkSize = 10_000)))
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("blanker filter -> VALIDATION_ERROR(filter)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("filter" to "   "))))
        }
        ex.violations.first().field shouldBe "filter"
    }

    test("non-blank filter ist zulaessig (Plan-konformes Filter-DSL-Parsing in Runner-Layer)") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(
            ctx(args(extraFields = mapOf("filter" to "tenant_id = :tenant"))),
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("sinceColumn ohne since -> VALIDATION_ERROR(since)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("sinceColumn" to "updated_at"))))
        }
        ex.violations.first().field shouldBe "since"
    }

    test("since ohne sinceColumn -> VALIDATION_ERROR(sinceColumn)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("since" to "2026-05-01T00:00:00Z"))))
        }
        ex.violations.first().field shouldBe "sinceColumn"
    }

    test("sinceColumn + since beide gesetzt + valid identifier -> Success") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(
            ctx(
                args(
                    extraFields = mapOf(
                        "sinceColumn" to "updated_at",
                        "since" to "2026-05-01T00:00:00Z",
                    ),
                ),
            ),
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("sinceColumn mit qualified identifier (schema.column) ist zulaessig") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(
            ctx(
                args(
                    extraFields = mapOf(
                        "sinceColumn" to "events.updated_at",
                        "since" to "2026-05-01",
                    ),
                ),
            ),
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("sinceColumn mit SQL-Injection-Pattern -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(
                    args(
                        extraFields = mapOf(
                            "sinceColumn" to "updated_at; DROP TABLE users",
                            "since" to "2026-05-01",
                        ),
                    ),
                ),
            )
        }
        ex.violations.first().field shouldBe "sinceColumn"
    }
})
