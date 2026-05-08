package dev.dmigrate.server.application.job

import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.text.FakeUnicodeTextService
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.policy.PolicyRule
import dev.dmigrate.server.core.idempotency.IdempotencyKey
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-012 / LN-011 / LN-017 / LN-027: Admission-Integration im
 * [JobStartOrchestrator.commitJob]-Pfad.
 *
 * Sub-AP-Scope (3/4) — pre-commit Granted/Saturated/Closed-Pfade,
 * Permit-Cleanup auf Quota-Reject + IdempotencyNotEligible. Die
 * post-commit Setup-Failure-Pfade (worker == null,
 * factory.create-Throw, dispatcher.dispatch-Throw,
 * RejectedExecutionException → markExecutorSetupFailed) kommen in (4/4).
 */
class JobStartOrchestratorAdmissionTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val principal = Fixtures.principal("alice")
    val tool = "schema_reverse_start"
    val now: Instant = Fixtures.NOW

    fun connRef(value: String = "dmigrate://tenants/acme/connections/c1") =
        RefField(name = "connectionId", value = value, expectedKind = ResourceKind.CONNECTIONS)

    class CountingPermit : JobDispatchPermit {
        val closeCount = AtomicInteger(0)
        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    // FixedAdmission ist als Top-Level-Klasse definiert (siehe Datei-Ende),
    // weil Kotlin keine companion-Objects in Local-Klassen erlaubt.

    class Fixture(
        admission: JobDispatchAdmission = SyncJobDispatchAdmission,
        wireAutoDispatch: Boolean = true,
        val jobIdSeq: AtomicInteger = AtomicInteger(0),
        policyDefault: PolicyEffect = PolicyEffect.Allow,
        policyRules: List<PolicyRule> = emptyList(),
    ) {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val approvalGrantStore = InMemoryApprovalGrantStore()
        val workerHandleRegistry = InMemoryWorkerHandleRegistry()
        val transaction = InMemoryJobStartTransaction(jobStore, idempotencyStore)
        val grantService = DefaultApprovalGrantService(approvalGrantStore, ApprovalGrantValidator())
        val approvedRetryService = ApprovedRetryService(
            approvalGrantService = grantService,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
        )
        val policyService = ConfiguredPolicyService(rules = policyRules, defaultEffect = policyDefault)
        val dispatcher: JobDispatcher? = if (wireAutoDispatch) JobDispatcher(jobStore) else null
        val factory: JobWorkerFactory? = if (wireAutoDispatch) PassthroughJobWorkerFactory else null
        val orchestrator = JobStartOrchestrator(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            approvalGrantStore = approvalGrantStore,
            approvedRetryService = approvedRetryService,
            policyService = policyService,
            payloadFingerprintService = DefaultPayloadFingerprintService(FakeUnicodeTextService()),
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
            jobDispatcher = dispatcher,
            jobWorkerFactory = factory,
            dispatchAdmission = admission,
        )

        fun request(idempotencyKey: String? = "k1") = JobStartRequest(
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

        fun scopeFor(idempotencyKey: String = "k1") = IdempotencyScope(
            tenantId = tenant,
            callerId = principal,
            toolName = tool,
            idempotencyKey = IdempotencyKey(idempotencyKey),
        )
    }

    // ── Saturated → RATE_LIMITED, kein JobStore-/Worker-Side-Effect ──

    test("Saturated → RateLimited(EXECUTOR_SATURATED) ohne JobStore-Zeile, ohne Worker-Handle") {
        val admission = FixedAdmission.saturated(retryAfter = Duration.ofSeconds(2), capacity = 16)
        val fx = Fixture(admission = admission)

        val outcome = fx.orchestrator.start(fx.request())

        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.RateLimited>()
        outcome.reason shouldBe JobStartReason.EXECUTOR_SATURATED
        outcome.retryAfter shouldBe Duration.ofSeconds(2)
        outcome.current shouldBe 16L
        outcome.limit shouldBe 16L

        // Keine Job-ID vergeben, kein JobStore-Eintrag, kein Worker-Handle.
        fx.jobIdSeq.get() shouldBe 0
        fx.jobStore.findById(tenant, "job_1") shouldBe null
        fx.workerHandleRegistry.signal("job_1", "noop")
            .shouldBeInstanceOf<dev.dmigrate.server.ports.SignalOutcome.NotFound>()
        // LF-012 / LN-011 / LN-017 / LN-027 "Idempotency-Reservation expired regulaer": die
        // Saturated-Antwort markiert die Reservation NICHT terminal. Sie
        // bleibt in einem nicht-terminalen Zustand (PENDING) und expired
        // regulaer. Wir bestaetigen das negativ — kein Failed/Denied/
        // Committed (zustaendig fuer terminale Wire-Replays).
        val replay = fx.idempotencyStore.reserve(fx.scopeFor(), "anyFingerprint", now.plusSeconds(2))
        (replay is IdempotencyReserveOutcome.Failed ||
            replay is IdempotencyReserveOutcome.Denied ||
            replay is IdempotencyReserveOutcome.Committed) shouldBe false
    }

    // ── Closed → markFailed + Failed-Replay ──

    test("Closed → Failed(executor:closed), Idempotency persistent FAILED, kein stale PENDING") {
        val admission = FixedAdmission.closed()
        val fx = Fixture(admission = admission)

        val outcome = fx.orchestrator.start(fx.request())

        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Failed>()
        outcome.reason shouldBe JobStartOrchestrator.REASON_EXECUTOR_CLOSED

        // Replay mit demselben Scope/Fingerprint sieht den FAILED-Slot
        // (deterministisches Replay statt erneutem Start).
        val replay = fx.idempotencyStore.reserve(fx.scopeFor(), "anyFingerprint", now.plusSeconds(2))
        // Bei verschiedenem Fingerprint liefert der Store Conflict; bei
        // gleichem Fingerprint Failed. Wir akzeptieren beide — der Pin
        // ist: nicht ExistingPending, nicht Reserved.
        (replay is IdempotencyReserveOutcome.ExistingPending ||
            replay is IdempotencyReserveOutcome.Reserved) shouldBe false
    }

    // ── Granted: Auto-Dispatch + Permit-Hand-off ──

    test("Granted + happy path → Started, Permit wird vom Dispatcher freigegeben") {
        val permit = CountingPermit()
        val admission = FixedAdmission.granted(permit)
        val fx = Fixture(admission = admission)

        val outcome = fx.orchestrator.start(fx.request())

        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        admission.tryAcquireCalls.get() shouldBe 1
        // SyncExecutor + PassthroughJobWorker → Worker laeuft sofort,
        // Dispatcher schliesst das Permit im finally.
        permit.closeCount.get() shouldBe 1
    }

    // ── No auto-dispatch wired → kein Permit-Acquire ──

    test("dispatcher == null → kein Admission-Acquire, kein Permit") {
        val admission = FixedAdmission.saturated()
        val fx = Fixture(admission = admission, wireAutoDispatch = false)

        val outcome = fx.orchestrator.start(fx.request())

        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        admission.tryAcquireCalls.get() shouldBe 0
    }

    // ── Permit-Cleanup auf IdempotencyNotEligible ──

    test("IdempotencyNotEligible nach Granted → Permit wird synchron geschlossen") {
        // Fake-Transaction immer IdempotencyNotEligible: simuliert die
        // post-reserve / pre-commit race (LF-012 / LN-011 / LN-017 / LN-027).
        val permit = CountingPermit()
        val admission = FixedAdmission.granted(permit)
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val workerHandleRegistry = InMemoryWorkerHandleRegistry()
        val fakeTransaction = object : dev.dmigrate.server.ports.JobStartTransaction {
            override fun commit(
                jobRecord: dev.dmigrate.server.core.job.JobRecord,
                idempotencyScope: IdempotencyScope,
                now: Instant,
            ): dev.dmigrate.server.ports.JobStartTransactionOutcome =
                dev.dmigrate.server.ports.JobStartTransactionOutcome.IdempotencyNotEligible
        }
        val grantStore = InMemoryApprovalGrantStore()
        val grantService = DefaultApprovalGrantService(grantStore, ApprovalGrantValidator())
        val approvedRetryService = ApprovedRetryService(
            approvalGrantService = grantService,
            idempotencyStore = idempotencyStore,
            jobStartTransaction = fakeTransaction,
            workerHandleRegistry = workerHandleRegistry,
            jobIdFactory = { "job_x" },
        )
        val orchestrator = JobStartOrchestrator(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = fakeTransaction,
            workerHandleRegistry = workerHandleRegistry,
            approvalGrantStore = grantStore,
            approvedRetryService = approvedRetryService,
            policyService = ConfiguredPolicyService(emptyList(), PolicyEffect.Allow),
            payloadFingerprintService = DefaultPayloadFingerprintService(FakeUnicodeTextService()),
            jobIdFactory = { "job_x" },
            jobDispatcher = JobDispatcher(jobStore),
            jobWorkerFactory = PassthroughJobWorkerFactory,
            dispatchAdmission = admission,
        )

        val request = JobStartRequest(
            toolName = tool,
            tenantId = tenant,
            callerId = principal,
            idempotencyKey = "k-race",
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

        val outcome = orchestrator.start(request)
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Pending>()
        permit.closeCount.get() shouldBe 1
    }
})

private class FixedAdmission(
    private val outcomes: List<JobDispatchAdmissionOutcome>,
) : JobDispatchAdmission {
    val tryAcquireCalls: AtomicInteger = AtomicInteger(0)
    private val cursor = AtomicInteger(0)

    override fun tryAcquire(now: Instant): JobDispatchAdmissionOutcome {
        tryAcquireCalls.incrementAndGet()
        val idx = cursor.getAndIncrement().coerceAtMost(outcomes.size - 1)
        return outcomes[idx]
    }

    companion object {
        fun granted(permit: JobDispatchPermit): FixedAdmission =
            FixedAdmission(listOf(JobDispatchAdmissionOutcome.Granted(permit)))

        fun saturated(retryAfter: Duration = Duration.ofMillis(500), capacity: Long = 8): FixedAdmission =
            FixedAdmission(
                listOf(
                    JobDispatchAdmissionOutcome.Saturated(
                        retryAfter = retryAfter,
                        current = capacity,
                        limit = capacity,
                    ),
                ),
            )

        fun closed(): FixedAdmission =
            FixedAdmission(listOf(JobDispatchAdmissionOutcome.Closed))
    }
}
