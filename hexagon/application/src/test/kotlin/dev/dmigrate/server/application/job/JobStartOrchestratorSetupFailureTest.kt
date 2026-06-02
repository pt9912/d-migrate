package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationTokenSource
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.text.FakeUnicodeTextService
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.DefaultQuotaService
import dev.dmigrate.server.application.quota.InMemoryQuotaReservationOwnerStore
import dev.dmigrate.server.application.quota.OwnerAwareQuotaService
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-012 / LN-011 / LN-017 / LN-027: post-commit Setup-Failure-Pfade in
 * [JobStartOrchestrator]. Sub-AP-Scope (4/4): worker == null,
 * factory.create-Throw, dispatcher.dispatch-Throw,
 * RejectedExecutionException, workerHandleRegistry.register-Throw,
 * sowie Cleanup-Pfade (Quota-Release / Handle-Unregister) als
 * best-effort.
 */
class JobStartOrchestratorSetupFailureTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val principal = Fixtures.principal("alice")
    val tool = "schema_reverse_start"
    val now: Instant = Fixtures.NOW

    fun connRef(value: String = "dmigrate://tenants/acme/connections/c1") =
        RefField(name = "connectionId", value = value, expectedKind = ResourceKind.CONNECTIONS)

    fun request(idempotencyKey: String = "k1") = JobStartRequest(
        toolName = tool,
        tenantId = tenant,
        callerId = principal,
        idempotencyKey = idempotencyKey,
        approvalToken = null,
        payload = JsonValue.obj("connectionId" to JsonValue.str("c1")),
        refs = listOf(connRef()),
        now = now,
        jobBuilder = { jobId, createdAt ->
            Fixtures.jobRecord(jobId).copy(
                managedJob = Fixtures.jobRecord(jobId).managedJob.copy(
                    status = JobStatus.QUEUED,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            )
        },
    )

    fun buildOrchestrator(
        factory: JobWorkerFactory,
        dispatcher: JobDispatcher,
        jobStoreRef: JobStore,
        idempotencyStore: InMemoryIdempotencyStore = InMemoryIdempotencyStore(),
        workerHandleRegistry: dev.dmigrate.server.ports.WorkerHandleRegistry = InMemoryWorkerHandleRegistry(),
        quotaService: OwnerAwareQuotaService? = null,
        admission: JobDispatchAdmission = SyncJobDispatchAdmission,
    ): JobStartOrchestrator {
        val grantStore = InMemoryApprovalGrantStore()
        val grantService = DefaultApprovalGrantService(grantStore, ApprovalGrantValidator())
        val transaction = InMemoryJobStartTransaction(jobStoreRef, idempotencyStore)
        val approvedRetryService = ApprovedRetryService(
            approvalGrantService = grantService,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            jobIdFactory = { "job_x" },
        )
        return JobStartOrchestrator(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            approvalGrantStore = grantStore,
            approvedRetryService = approvedRetryService,
            policyService = ConfiguredPolicyService(emptyList(), PolicyEffect.Allow),
            payloadFingerprintService = DefaultPayloadFingerprintService(FakeUnicodeTextService()),
            jobIdFactory = { "job_failure" },
            jobDispatcher = dispatcher,
            jobWorkerFactory = factory,
            jobStore = jobStoreRef,
            quotaService = quotaService,
            dispatchAdmission = admission,
        )
    }

    test("worker == null → markFailed(WORKER_NOT_REGISTERED), Started returned, handle unregistered") {
        val jobStore = InMemoryJobStore()
        val registry = TrackingRegistry()
        val orchestrator = buildOrchestrator(
            factory = NullFactory,
            dispatcher = JobDispatcher(jobStore),
            jobStoreRef = jobStore,
            workerHandleRegistry = registry,
        )

        val outcome = orchestrator.start(request())

        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        val terminal = jobStore.findById(tenant, "job_failure").shouldNotBeNull()
        terminal.managedJob.status shouldBe JobStatus.FAILED
        terminal.managedJob.error?.code shouldBe "WORKER_NOT_REGISTERED"
        registry.registerCalls.get() shouldBe 1
        registry.unregisterCalls.get() shouldBe 1
    }

    test("factory.create wirft → markFailed(EXECUTOR_SETUP_FAILED), Permit close") {
        val jobStore = InMemoryJobStore()
        val permit = CountingPermit()
        val orchestrator = buildOrchestrator(
            factory = ThrowingFactory(IllegalStateException("factory boom")),
            dispatcher = JobDispatcher(jobStore),
            jobStoreRef = jobStore,
            admission = FixedPermitAdmission(permit),
        )

        val outcome = orchestrator.start(request())

        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        val terminal = jobStore.findById(tenant, "job_failure").shouldNotBeNull()
        terminal.managedJob.status shouldBe JobStatus.FAILED
        terminal.managedJob.error?.code shouldBe "EXECUTOR_SETUP_FAILED"
        permit.closeCount.get() shouldBe 1
    }

    test("dispatcher.dispatch wirft RejectedExecutionException → markFailed(EXECUTOR_CLOSED)") {
        val jobStore = InMemoryJobStore()
        val rejectingExecutor = Executor { throw RejectedExecutionException("pool closed") }
        val dispatcher = JobDispatcher(jobStore, executor = rejectingExecutor)
        val permit = CountingPermit()
        val orchestrator = buildOrchestrator(
            factory = SuccessFactory,
            dispatcher = dispatcher,
            jobStoreRef = jobStore,
            admission = FixedPermitAdmission(permit),
        )

        val outcome = orchestrator.start(request())

        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        val terminal = jobStore.findById(tenant, "job_failure").shouldNotBeNull()
        terminal.managedJob.status shouldBe JobStatus.FAILED
        terminal.managedJob.error?.code shouldBe "EXECUTOR_CLOSED"
        permit.closeCount.get() shouldBe 1
    }

    test("workerHandleRegistry.register wirft → markFailed, unregister best-effort trotz Throw") {
        val jobStore = InMemoryJobStore()
        val registry = ThrowingRegistry(throwOnRegister = IllegalStateException("register boom"))
        val orchestrator = buildOrchestrator(
            factory = SuccessFactory,
            dispatcher = JobDispatcher(jobStore),
            jobStoreRef = jobStore,
            workerHandleRegistry = registry,
        )

        val outcome = orchestrator.start(request())

        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        val terminal = jobStore.findById(tenant, "job_failure").shouldNotBeNull()
        terminal.managedJob.status shouldBe JobStatus.FAILED
        terminal.managedJob.error?.code shouldBe "EXECUTOR_SETUP_FAILED"
        // register-throw → handleRegistrationAttempted=true → unregister
        // best-effort gerufen.
        registry.unregisterCalls.get() shouldBe 1
    }

    test("worker == null mit Quota → Setup-Failure released den Quota-Owner") {
        val jobStore = InMemoryJobStore()
        // Eine vollstaendige OwnerAwareQuotaService-Komposition ueber
        // InMemory-Stores. reserve/commit/release lebt produktiv,
        // der release-Pfad wird vom markExecutorSetupFailed-Helper
        // ueber releaseSetupQuotaBestEffort getroffen.
        val ownerStore = InMemoryQuotaReservationOwnerStore()
        val quotaStore = InMemoryQuotaStore()
        val quotaService = OwnerAwareQuotaService(
            delegate = DefaultQuotaService(quotaStore) { _ -> 10L },
            ownerStore = ownerStore,
        )
        val orchestrator = buildOrchestrator(
            factory = NullFactory,
            dispatcher = JobDispatcher(jobStore),
            jobStoreRef = jobStore,
            quotaService = quotaService,
        )

        val outcome = orchestrator.start(request())

        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        val terminal = jobStore.findById(tenant, "job_failure").shouldNotBeNull()
        terminal.managedJob.status shouldBe JobStatus.FAILED
        terminal.managedJob.error?.code shouldBe "WORKER_NOT_REGISTERED"
        // LF-012 / LN-011 / LN-017 / LN-027: setup-failure released den Owner. Owner-Status
        // ist RELEASED nach markExecutorSetupFailed.
        val ownerId = "acme:alice:schema_reverse_start:k1"
        val owner = ownerStore.findById(ownerId).shouldNotBeNull()
        owner.status shouldBe dev.dmigrate.server.application.quota.QuotaReservationStatus.RELEASED
    }

    test("JobStore-Fehler beim Markieren propagiert (NICHT suppressed)") {
        val backing = InMemoryJobStore()
        val throwingStore = ThrowingJobStore(backing, throwOnTransition = RuntimeException("store boom"))
        val orchestrator = buildOrchestrator(
            factory = NullFactory,
            dispatcher = JobDispatcher(throwingStore),
            jobStoreRef = throwingStore,
        )

        // LF-012 / LN-011 / LN-017 / LN-027: JobStore-Throw beim primaeren markExecutorSetupFailed
        // wird NICHT verschluckt — der Caller sieht eine echte Exception,
        // weil ohne pollbare FAILED-Wahrheit ein Started-Return luegen
        // wuerde.
        shouldThrow<RuntimeException> {
            orchestrator.start(request())
        }
    }
})

private object SuccessFactory : JobWorkerFactory {
    override fun create(record: JobRecord, request: JobStartRequest): JobWorker =
        JobWorker { _, _ -> JobWorkerOutcome.Succeeded() }
}

private object NullFactory : JobWorkerFactory {
    override fun create(record: JobRecord, request: JobStartRequest): JobWorker? = null
}

private class ThrowingFactory(private val cause: Throwable) : JobWorkerFactory {
    override fun create(record: JobRecord, request: JobStartRequest): JobWorker = throw cause
}

private class CountingPermit : JobDispatchPermit {
    val closeCount: AtomicInteger = AtomicInteger(0)
    override fun close() {
        closeCount.incrementAndGet()
    }
}

private class FixedPermitAdmission(private val permit: JobDispatchPermit) : JobDispatchAdmission {
    override fun tryAcquire(now: Instant): JobDispatchAdmissionOutcome =
        JobDispatchAdmissionOutcome.Granted(permit)
}

private class TrackingRegistry : dev.dmigrate.server.ports.WorkerHandleRegistry {
    val registerCalls: AtomicInteger = AtomicInteger(0)
    val unregisterCalls: AtomicInteger = AtomicInteger(0)
    private val backing = InMemoryWorkerHandleRegistry()

    override fun register(jobId: String, source: CancellationTokenSource) {
        registerCalls.incrementAndGet()
        backing.register(jobId, source)
    }

    override fun signal(jobId: String, reason: String?) = backing.signal(jobId, reason)

    override fun unregister(jobId: String) {
        unregisterCalls.incrementAndGet()
        backing.unregister(jobId)
    }
}

private class ThrowingRegistry(
    private val throwOnRegister: Throwable? = null,
    private val throwOnUnregister: Throwable? = null,
) : dev.dmigrate.server.ports.WorkerHandleRegistry {
    val unregisterCalls: AtomicInteger = AtomicInteger(0)
    private val backing = InMemoryWorkerHandleRegistry()

    override fun register(jobId: String, source: CancellationTokenSource) {
        if (throwOnRegister != null) throw throwOnRegister
        backing.register(jobId, source)
    }

    override fun signal(jobId: String, reason: String?) = backing.signal(jobId, reason)

    override fun unregister(jobId: String) {
        unregisterCalls.incrementAndGet()
        if (throwOnUnregister != null) throw throwOnUnregister
        backing.unregister(jobId)
    }
}


private class ThrowingJobStore(
    private val backing: JobStore,
    private val throwOnTransition: Throwable,
) : JobStore by backing {
    override fun transitionStatus(
        tenantId: dev.dmigrate.server.core.principal.TenantId,
        jobId: String,
        allowedFromStatuses: Set<JobStatus>,
        transformer: (dev.dmigrate.server.core.job.ManagedJob) -> dev.dmigrate.server.core.job.ManagedJob,
    ): dev.dmigrate.server.ports.JobTransitionOutcome {
        // Erste Transition (QUEUED → FAILED in markExecutorSetupFailed) wirft.
        throw throwOnTransition
    }
}
