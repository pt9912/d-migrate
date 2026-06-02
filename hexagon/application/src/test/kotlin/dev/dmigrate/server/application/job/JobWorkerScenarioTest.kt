package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.core.cancel.OperationCancelSource
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.core.model.SchemaDefinition
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.connection.ConnectionConfig
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.connection.ConnectionMaterializer
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.text.FakeUnicodeTextService
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-012 / LN-011 / LN-017 / LN-027: JobStartOrchestrator
 * → JobDispatcher → konkreter JobWorker.
 *
 * Verbindet alle Job-Start- und Dispatch-Bausteine in einer realen Aufruf-Sequenz und
 * belegt LF-012 / LN-011 / LN-017 / LN-027-Akzeptanzpunkte:
 *
 * - Erfolgreicher Reverse-Job publiziert Artefakt + JobRecord wechselt
 *   QUEUED → RUNNING → SUCCEEDED.
 * - Worker-Cancel mit JOB_CANCEL → JobRecord wechselt auf CANCELLED,
 *   nicht FAILED.
 * - Worker-Cancel mit RUNNER_TIMEOUT → JobRecord wechselt auf FAILED
 *   mit error.code = OPERATION_TIMEOUT, NICHT auf CANCELLED.
 *
 * Bewusst NICHT in diesem Test:
 *
 * - MCP-Tool-Handler-Pfad (das deckt McpJobStartScenarioTest aus
 *   LF-012 / LN-011 / LN-017 / LN-027 ab; der hier testet die rein application-seitige Kette).
 * - Produktive ConnectionMaterializer-/JobArtifactPublisher-Adapter
 *   (Stub-Lambdas reichen fuer den Wire-Beweis).
 */
class JobWorkerScenarioTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val principal = Fixtures.principal("alice")
    val clock: Clock = Clock.fixed(Fixtures.NOW, ZoneOffset.UTC)
    val tool = "schema_reverse_start"

    class Fixture(
        val policyEffect: PolicyEffect = PolicyEffect.Allow,
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
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyEffect),
            payloadFingerprintService = DefaultPayloadFingerprintService(FakeUnicodeTextService()),
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
        )
        val dispatcher = JobDispatcher(jobStore = jobStore, clock = clock)

        fun startRequest(
            idempotencyKey: String = "k1",
            connectionRef: String = "dmigrate://tenants/acme/connections/c1",
        ) = JobStartRequest(
            toolName = tool,
            tenantId = tenant,
            callerId = principal,
            idempotencyKey = idempotencyKey,
            approvalToken = null,
            payload = JsonValue.obj("connectionId" to JsonValue.str(connectionRef)),
            refs = listOf(RefField("connectionId", connectionRef, ResourceKind.CONNECTIONS)),
            now = Fixtures.NOW,
            jobBuilder = { jobId, createdAt ->
                JobRecord(
                    managedJob = ManagedJob(
                        jobId = jobId,
                        operation = "schema_reverse",
                        status = JobStatus.QUEUED,
                        createdAt = createdAt,
                        updatedAt = createdAt,
                        expiresAt = createdAt.plusSeconds(86_400),
                        createdBy = principal.value,
                    ),
                    tenantId = tenant,
                    ownerPrincipalId = principal,
                    visibility = JobVisibility.OWNER,
                    resourceUri = ServerResourceUri(tenant, ResourceKind.JOBS, jobId),
                )
            },
        )
    }

    fun stubMaterializer() = ConnectionMaterializer { _, _ ->
        ConnectionConfig(
            dialect = DatabaseDialect.POSTGRESQL,
            host = "localhost", port = null, database = "test",
            user = null, password = null,
        )
    }

    fun stubPublisher(prefix: String = "dmigrate://tenants/acme/artifacts/") =
        JobArtifactPublisher { job, _ -> prefix + job.managedJob.jobId }

    test("End-to-End Reverse-Job: orchestrator commits QUEUED → dispatcher runs worker → SUCCEEDED + artifact") {
        val fx = Fixture(policyEffect = PolicyEffect.Allow)
        val started = fx.orchestrator.start(fx.startRequest()) as JobStartHandlerOutcome.Started

        // Job-Record ist QUEUED unmittelbar nach dem Commit.
        fx.jobStore.findById(tenant, started.jobId)!!.managedJob.status shouldBe JobStatus.QUEUED

        // Worker konstruieren mit StubLoader/Publisher (wie es der
        // produktive Tool-Handler in einer noch zu wireenden Folge-AP
        // tun wird).
        val worker = SchemaReverseJobWorker(
            connectionRef = "dmigrate://tenants/acme/connections/c1",
            materializer = stubMaterializer(),
            readSchema = { _, _ -> SchemaDefinition(name = "reversed", version = "1") },
            publisher = stubPublisher(),
        )

        val outcome = fx.dispatcher.dispatch(started.record, worker, started.cancellationSource.token).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>()
        outcome.artifactRefs shouldHaveSize 1

        val final = fx.jobStore.findById(tenant, started.jobId)!!
        final.managedJob.status shouldBe JobStatus.SUCCEEDED
        final.managedJob.artifacts shouldBe listOf("dmigrate://tenants/acme/artifacts/${started.jobId}")
    }

    test("End-to-End mit JOB_CANCEL: Worker propagiert Cancel mit Default-Source → CANCELLED") {
        val fx = Fixture(policyEffect = PolicyEffect.Allow)
        val started = fx.orchestrator.start(fx.startRequest()) as JobStartHandlerOutcome.Started

        // Worker-Stub, der JOB_CANCEL-Source wirft (Default).
        val worker = JobWorker { _, _ ->
            throw OperationCancelledException(reason = "user-cancel")
        }

        // Cancel-Token vom orchestrator stammt aus der Worker-Handle-
        // Registry. Ihn manuell signalisieren ist auch OK fuer den
        // E2E-Beweis.
        val cancelSource = CancellationTokenSource.create()
        cancelSource.cancel("user-cancel")

        val outcome = fx.dispatcher.dispatch(started.record, worker, cancelSource.token).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Cancelled>()
        outcome.reason shouldBe "user-cancel"

        val final = fx.jobStore.findById(tenant, started.jobId)!!
        final.managedJob.status shouldBe JobStatus.CANCELLED
        final.managedJob.cancelRequest.signalAcked shouldBe true
        // Default-Scrubber laesst harmlose Reasons unveraendert.
        final.managedJob.cancelRequest.requestedReason shouldBe "user-cancel"
    }

    test("End-to-End mit RUNNER_TIMEOUT: Worker propagiert mit RUNNER_TIMEOUT-Source → FAILED(OPERATION_TIMEOUT)") {
        val fx = Fixture(policyEffect = PolicyEffect.Allow)
        val started = fx.orchestrator.start(fx.startRequest()) as JobStartHandlerOutcome.Started

        val worker = JobWorker { _, _ ->
            throw OperationCancelledException(
                reason = "runner-budget-exhausted",
                source = OperationCancelSource.RUNNER_TIMEOUT,
            )
        }

        val outcome = fx.dispatcher.dispatch(started.record, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
        outcome.errorCode shouldBe JobDispatcher.ERROR_CODE_OPERATION_TIMEOUT

        val final = fx.jobStore.findById(tenant, started.jobId)!!
        // LF-012 / LN-011 / LN-017 / LN-027: RUNNER_TIMEOUT nicht als CANCELLED gemapped.
        final.managedJob.status shouldBe JobStatus.FAILED
        final.managedJob.error?.code shouldBe "OPERATION_TIMEOUT"
        final.managedJob.cancelRequest.signalAcked shouldBe false
    }

    test("End-to-End: Worker wirft generische Exception → FAILED(RUNNER_ERROR), nicht CANCELLED") {
        val fx = Fixture(policyEffect = PolicyEffect.Allow)
        val started = fx.orchestrator.start(fx.startRequest()) as JobStartHandlerOutcome.Started

        val worker = JobWorker { _, _ -> throw IllegalStateException("driver-down") }
        val outcome = fx.dispatcher.dispatch(started.record, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
        outcome.errorCode shouldBe JobDispatcher.REASON_RUNNER_ERROR

        val final = fx.jobStore.findById(tenant, started.jobId)!!
        final.managedJob.status shouldBe JobStatus.FAILED
        final.managedJob.cancelRequest.signalAcked shouldBe false
    }

    test("End-to-End mit Compare-Worker: Cancel zwischen Source- und Target-Load verhindert Diff/Publish") {
        // LF-012 / LN-011 / LN-017 / LN-027 Test "Cancel vor oder waehrend Compare-Materialisierung
        // verhindert Diff- und Artefakt-Publish".
        val fx = Fixture(policyEffect = PolicyEffect.Allow)
        val started = fx.orchestrator.start(
            fx.startRequest(idempotencyKey = "k-compare", connectionRef = "dmigrate://tenants/acme/connections/c1"),
        ) as JobStartHandlerOutcome.Started

        val cancelSource = CancellationTokenSource.create()
        var loadCount = 0
        var publishCalled = false
        val worker = SchemaCompareJobWorker(
            sourceRef = "dmigrate://tenants/acme/schemas/s1",
            targetRef = "dmigrate://tenants/acme/schemas/s2",
            schemaLoader = { _, _, _ ->
                loadCount++
                if (loadCount == 1) cancelSource.cancel("mid-materialisierung")
                SchemaDefinition(name = "x", version = "1")
            },
            comparator = { _, _ ->
                error("must not reach compare after cancel")
            },
            publisher = dev.dmigrate.server.application.job.JobArtifactPublisher { _, _ ->
                publishCalled = true
                "dmigrate://x"
            },
        )

        val outcome = fx.dispatcher.dispatch(started.record, worker, cancelSource.token).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Cancelled>()
        loadCount shouldBe 1
        publishCalled shouldBe false

        val final = fx.jobStore.findById(tenant, started.jobId)!!
        final.managedJob.status shouldBe JobStatus.CANCELLED
    }
})
