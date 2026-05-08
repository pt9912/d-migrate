package dev.dmigrate.mcp.registry

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.job.ApprovedRetryService
import dev.dmigrate.server.application.job.JobStartOrchestrator
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.connection.ConnectionSensitivity
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryConnectionReferenceStore
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
 * LF-010 / LF-013 / LN-009 / LN-011 § 6.2 + § 8.8 (F.8 2/4) — Pre-Idempotency-Validation +
 * LF-012 / LN-011 / LN-017 / LN-027-Pipeline-Integration des `data_transfer_start`-Handlers.
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
        val tenant: TenantId = Fixtures.tenant("acme"),
        seedDefaultConnections: Boolean = true,
    ) {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val workerHandleRegistry = InMemoryWorkerHandleRegistry()
        val approvalGrantStore = InMemoryApprovalGrantStore()
        val connectionStore = InMemoryConnectionReferenceStore()
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
        val handler = DataTransferStartHandler(orchestrator, connectionStore, clock)

        init {
            if (seedDefaultConnections) {
                seedConnection("source-db")
                seedConnection("target-db")
            }
        }

        fun seedConnection(connectionId: String) {
            connectionStore.save(
                ConnectionReference(
                    connectionId = connectionId,
                    tenantId = tenant,
                    displayName = connectionId,
                    dialectId = "postgres",
                    sensitivity = ConnectionSensitivity.NON_PRODUCTION,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.CONNECTIONS, connectionId),
                ),
            )
        }
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

    test("chunkSize=10000 (Vertragsmaximum) ist zulaessig") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(ctx(args(chunkSize = 10_000)))
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("ungueltiger onConflict-Wert -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("onConflict" to "replace"))))
        }
        ex.violations.first().field shouldBe "onConflict"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("ungueltiger triggerMode-Wert -> VALIDATION_ERROR") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("triggerMode" to "copy"))))
        }
        ex.violations.first().field shouldBe "triggerMode"
        fx.jobIdSeq.get() shouldBe 0
    }

    test("blanker filter -> VALIDATION_ERROR(filter)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(ctx(args(extraFields = mapOf("filter" to "   "))))
        }
        ex.violations.first().field shouldBe "filter"
    }

    test("non-blank filter ist zulaessig und wird fuer Fingerprint kanonisiert") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val result = fx.handler.handle(
            ctx(args(extraFields = mapOf("filter" to "tenant_id = 'tenant'"))),
        )
        result.shouldBeInstanceOf<ToolCallOutcome.Success>()
    }

    test("semantisch gleicher filter mit whitespace und WHERE-case replayt denselben Job") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val first = fx.handler.handle(
            ctx(args(idempotencyKey = "k-filter-canon", extraFields = mapOf("filter" to "WHERE tenant_id = 'tenant'"))),
        )
        first.shouldBeInstanceOf<ToolCallOutcome.Success>()
        val second = fx.handler.handle(
            ctx(args(idempotencyKey = "k-filter-canon", extraFields = mapOf("filter" to "where  tenant_id='tenant'"))),
        )
        second.shouldBeInstanceOf<ToolCallOutcome.Success>()
        second.content.single().text!! shouldContain "\"jobId\":\"job_1\""
        fx.jobStore.list(
            fx.tenant,
            dev.dmigrate.server.core.pagination.PageRequest(pageSize = 10),
        ).items.size shouldBe 1
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

    // ──────────────────────────────────────────────────────────────
    // F.8 (3/4) — ConnectionRef-Resolution + Transfer-Fingerprint.
    // ──────────────────────────────────────────────────────────────

    test("sourceConnectionRef ohne Eintrag im Store -> RESOURCE_NOT_FOUND") {
        val fx = Fixture(seedDefaultConnections = false)
        fx.seedConnection("target-db")
        shouldThrow<ResourceNotFoundException> {
            fx.handler.handle(
                ctx(args(sourceConnectionRef = "dmigrate://tenants/acme/connections/missing-source")),
            )
        }
        fx.jobIdSeq.get() shouldBe 0
    }

    test("targetConnectionRef ohne Eintrag im Store -> RESOURCE_NOT_FOUND") {
        val fx = Fixture(seedDefaultConnections = false)
        fx.seedConnection("source-db")
        shouldThrow<ResourceNotFoundException> {
            fx.handler.handle(
                ctx(args(targetConnectionRef = "dmigrate://tenants/acme/connections/missing-target")),
            )
        }
    }

    test("sourceConnectionRef mit kind=jobs -> VALIDATION_ERROR(sourceConnectionRef)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(args(sourceConnectionRef = "dmigrate://tenants/acme/jobs/some-job")),
            )
        }
        ex.violations.first().field shouldBe "sourceConnectionRef"
    }

    test("targetConnectionRef mit kind=schemas -> VALIDATION_ERROR(targetConnectionRef)") {
        val fx = Fixture()
        val ex = shouldThrow<ValidationErrorException> {
            fx.handler.handle(
                ctx(args(targetConnectionRef = "dmigrate://tenants/acme/schemas/sch-1")),
            )
        }
        ex.violations.first().field shouldBe "targetConnectionRef"
    }

    test("Fingerprint umfasst Transfer-Optionen: gleicher idempotencyKey + anderer filter -> IDEMPOTENCY_CONFLICT") {
        val fx = Fixture(policyDefault = PolicyEffect.Allow)
        val first = fx.handler.handle(
            ctx(args(idempotencyKey = "k-fp", extraFields = mapOf("filter" to "tenant_id = 1"))),
        )
        first.shouldBeInstanceOf<ToolCallOutcome.Success>()

        // LF-012 / LN-011 / LN-017 / LN-027: "abweichende Transfer-Option mit gleichem
        // idempotencyKey -> IDEMPOTENCY_CONFLICT".
        shouldThrow<dev.dmigrate.server.application.error.IdempotencyConflictException> {
            fx.handler.handle(
                ctx(args(idempotencyKey = "k-fp", extraFields = mapOf("filter" to "tenant_id = 2"))),
            )
        }

        // Defense: nur EIN durabler Job angelegt.
        fx.jobStore.list(
            fx.tenant,
            dev.dmigrate.server.core.pagination.PageRequest(pageSize = 10),
        ).items.size shouldBe 1
    }
})
