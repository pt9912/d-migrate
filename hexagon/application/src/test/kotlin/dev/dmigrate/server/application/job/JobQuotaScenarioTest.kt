package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.OperationCancelSource
import dev.dmigrate.core.cancel.OperationCancelledException
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
import dev.dmigrate.server.application.quota.QuotaReservationStatus
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-012 / LN-011 / LN-017 / LN-027 End-to-End-Akzeptanz-Pins ueber den
 * vollen Orchestrator → Dispatcher → JobCancelService-Pfad mit
 * InMemory-Stores. Komplementiert die Wire-Tests aus
 * `McpJobStartScenarioTest` und die Sweeper-Tests aus LF-012 / LN-011 / LN-017 / LN-027.
 *
 * LF-012 / LN-011 / LN-017 / LN-027-Tests, die hier abgedeckt werden:
 *
 * - aktive Jobquote ueberschritten -> `RATE_LIMITED` (line 1301)
 * - `RATE_LIMITED` entsteht VOR jobBuilder-Aufruf — kein Secret-/
 *   Schema-/Pool-Touch (line 1302-1304)
 * - deduplizierter Retry verbraucht keine neue Quote (line 1307)
 * - Slot wird nach `succeeded`/`failed`/`cancelled` freigegeben
 *   (line 1313)
 * - Idempotency-Replay (Committed) ruft weder reserve noch refund
 *   (line 1312)
 *
 * Bewusst NICHT abgedeckt:
 *
 * - Start-Timeout / Runner-Timeout (LF-012 / LN-011 / LN-017 / LN-027, 1314-
 *   1316). Synchroner Worker-Pfad ohne echte Preemption macht den
 *   Timeout-Test wenig aussagekraeftig; sobald async-Executor
 *   Production-mode hat, kommt das in einer Follow-up-AP.
 * - Crash + Sweeper-Recovery: bereits in [QuotaReservationSweeperTest]
 *   abgedeckt.
 * - Parallele Reserve-Ueberbuchung: bereits via InMemoryQuotaStore
 *   `ConcurrentHashMap`-Atomicity in den Bestands-Tests abgedeckt.
 */
class JobQuotaScenarioTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val principal = Fixtures.principal("alice")
    val now: Instant = Fixtures.NOW
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    class Fixture(
        jobLimit: Long = 1L,
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
        val ownerStore = InMemoryQuotaReservationOwnerStore()
        val quotaStore = InMemoryQuotaStore()
        val quotaService = OwnerAwareQuotaService(
            delegate = DefaultQuotaService(quotaStore) { jobLimit },
            ownerStore = ownerStore,
        )
        val orchestrator = JobStartOrchestrator(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            approvedRetryService = approvedRetryService,
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = policyEffect),
            payloadFingerprintService = DefaultPayloadFingerprintService(FakeUnicodeTextService()),
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
            quotaService = quotaService,
        )
        val dispatcher = JobDispatcher(
            jobStore = jobStore,
            clock = clock,
            quotaService = quotaService,
        )
        val cancelService = JobCancelService(
            jobStore = jobStore,
            workerHandleRegistry = workerHandleRegistry,
            cancelReasonScrubber = { it },
            quotaService = quotaService,
        )

        var jobBuilderCalls = 0

        fun startRequest(idempotencyKey: String = "k1"): JobStartRequest = JobStartRequest(
            toolName = "schema_reverse_start",
            tenantId = tenant,
            callerId = principal,
            idempotencyKey = idempotencyKey,
            approvalToken = null,
            payload = JsonValue.obj("connectionId" to JsonValue.str("c1")),
            refs = emptyList(),
            now = now,
            jobBuilder = { jobId, createdAt ->
                jobBuilderCalls++
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

    fun PrincipalContext.scopedAdmin(): PrincipalContext =
        copy(scopes = setOf("dmigrate:admin"), isAdmin = true)

    test("Jobquote ueberschritten -> RateLimited mit retryAfter; jobBuilder NIE aufgerufen") {
        // LF-012 / LN-011 / LN-017 / LN-027: RATE_LIMITED muss VOR Secret-Reads,
        // Pool-Init, Runner-Materialisierung entstehen. Test pin't das,
        // indem jobBuilder NIE aufgerufen wird.
        val fx = Fixture(jobLimit = 1L)

        val first = fx.orchestrator.start(fx.startRequest("k-1"))
        first.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        fx.jobBuilderCalls shouldBe 1

        // Zweiter Start mit ANDEREM idempotencyKey -> neue Reservierung
        // versucht. Quota ist voll (limit=1, current=1) -> RateLimited.
        val builderCallsBefore = fx.jobBuilderCalls
        val second = fx.orchestrator.start(fx.startRequest("k-2"))
        second.shouldBeInstanceOf<JobStartHandlerOutcome.RateLimited>()
        // LF-012 / LN-011 / LN-017 / LN-027: jobBuilder NICHT aufgerufen,
        // also keine Secret-Materialisierung.
        fx.jobBuilderCalls shouldBe builderCallsBefore
        // retryAfter ist Pflicht; Default 30s.
        second.retryAfter.seconds shouldBe 30L
    }

    test("Deduplizierter Retry (gleicher idempotencyKey + fingerprint) verbraucht keine neue Quote") {
        // LF-012 / LN-011 / LN-017 / LN-027: deduplizierter Retry verbraucht keine
        // neue Quote.
        val fx = Fixture(jobLimit = 1L)

        // Erster Start belegt den einen Slot.
        fx.orchestrator.start(fx.startRequest("k-dedup"))
            .shouldBeInstanceOf<JobStartHandlerOutcome.Started>()

        // Zweiter Start mit IDENTISCHEM Scope+Fingerprint geht durch
        // Idempotency.reserve -> Committed -> AlreadyStarted, OHNE
        // Quota.reserve.
        val replay = fx.orchestrator.start(fx.startRequest("k-dedup"))
        replay.shouldBeInstanceOf<JobStartHandlerOutcome.AlreadyStarted>()

        // Counter bleibt bei 1 — kein Doppel-Reserve.
        // Verifikation ueber den Counter nicht direkt sichtbar; aber wenn
        // ein dritter Start mit anderem Key zurueckweist, ist klar dass
        // der erste Slot noch belegt ist.
        val third = fx.orchestrator.start(fx.startRequest("k-other"))
        third.shouldBeInstanceOf<JobStartHandlerOutcome.RateLimited>()
    }

    test("Slot wird nach SUCCEEDED freigegeben (Dispatcher-Release-Pfad)") {
        // LF-012 / LN-011 / LN-017 / LN-027: Slot wird nach succeeded/failed/cancelled
        // freigegeben.
        val fx = Fixture(jobLimit = 1L)
        val started = fx.orchestrator.start(fx.startRequest()) as JobStartHandlerOutcome.Started

        val worker = JobWorker { _, _ -> JobWorkerOutcome.Succeeded() }
        fx.dispatcher.dispatch(started.record, worker, CancellationToken.none()).get()

        // Owner-Status RELEASED.
        val ownerId = "${tenant.value}:${principal.value}:schema_reverse_start:k1"
        fx.ownerStore.findById(ownerId)!!.status shouldBe QuotaReservationStatus.RELEASED

        // Neuer Start mit anderem Key sollte jetzt durchgehen — Slot frei.
        val next = fx.orchestrator.start(fx.startRequest("k-after-success"))
        next.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
    }

    test("Slot wird nach FAILED freigegeben") {
        val fx = Fixture(jobLimit = 1L)
        val started = fx.orchestrator.start(fx.startRequest()) as JobStartHandlerOutcome.Started

        val worker = JobWorker { _, _ -> throw IllegalStateException("driver-down") }
        fx.dispatcher.dispatch(started.record, worker, CancellationToken.none()).get()

        val ownerId = "${tenant.value}:${principal.value}:schema_reverse_start:k1"
        fx.ownerStore.findById(ownerId)!!.status shouldBe QuotaReservationStatus.RELEASED
    }

    test("Slot wird nach CANCELLED freigegeben (Dispatcher-Pfad bei RUNNING-Worker-Cancel)") {
        val fx = Fixture(jobLimit = 1L)
        val started = fx.orchestrator.start(fx.startRequest()) as JobStartHandlerOutcome.Started

        val worker = JobWorker { _, _ -> JobWorkerOutcome.Cancelled(reason = "user-cancel") }
        fx.dispatcher.dispatch(started.record, worker, CancellationToken.none()).get()

        val ownerId = "${tenant.value}:${principal.value}:schema_reverse_start:k1"
        fx.ownerStore.findById(ownerId)!!.status shouldBe QuotaReservationStatus.RELEASED
    }

    test("Queued-Cancel via JobCancelService gibt Slot frei (LF-012 / LN-011 / LN-017 / LN-027)") {
        // Spezialfall: queued-Cancel laeuft NICHT durch den Dispatcher.
        // JobCancelService selbst muss den Slot freigeben.
        val fx = Fixture(jobLimit = 1L)
        val started = fx.orchestrator.start(fx.startRequest()) as JobStartHandlerOutcome.Started

        val cancelOutcome = fx.cancelService.cancel(
            jobIdOrUri = started.jobId,
            principal = Fixtures.principalContext(principalId = "alice", tenant = "acme"),
            reason = "no longer needed",
            now = now.plusSeconds(1),
        )
        cancelOutcome.shouldBeInstanceOf<JobCancelOutcome.Cancelled>()

        val ownerId = "${tenant.value}:${principal.value}:schema_reverse_start:k1"
        fx.ownerStore.findById(ownerId)!!.status shouldBe QuotaReservationStatus.RELEASED

        // Slot frei -> neuer Start moeglich.
        fx.orchestrator.start(fx.startRequest("k-after-cancel"))
            .shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
    }

    test("RUNNER_TIMEOUT-Source -> Failed(OPERATION_TIMEOUT) + Slot freigegeben (LF-012 / LN-011 / LN-017 / LN-027)") {
        // LF-012 / LN-011 / LN-017 / LN-027: Runner-Timeout setzt Jobstatus
        // failed(error.code=OPERATION_TIMEOUT) UND gibt aktive Slots frei.
        val fx = Fixture(jobLimit = 1L)
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

        // Slot freigegeben (LF-012 / LN-011 / LN-017 / LN-027: "gibt aktive Slots frei").
        val ownerId = "${tenant.value}:${principal.value}:schema_reverse_start:k1"
        fx.ownerStore.findById(ownerId)!!.status shouldBe QuotaReservationStatus.RELEASED
    }

    test("Idempotency-Replay (existing Committed) ruft weder reserve noch refund (LF-012 / LN-011 / LN-017 / LN-027)") {
        // Erste Start: Quota reserve + commit.
        val fx = Fixture(jobLimit = 1L)
        val first = fx.orchestrator.start(fx.startRequest("k-replay")) as JobStartHandlerOutcome.Started

        // Owner ist COMMITTED.
        val ownerId = "${tenant.value}:${principal.value}:schema_reverse_start:k-replay"
        val firstOwner = fx.ownerStore.findById(ownerId)!!
        firstOwner.status shouldBe QuotaReservationStatus.COMMITTED

        // Zweite Start mit gleichem Key+fingerprint -> AlreadyStarted
        // ueber Idempotency.Committed-Pfad, OHNE neuen Owner-Eintrag oder
        // Status-Aenderung. (Wenn reserve doch laeuft, wuerde register
        // eine IllegalArgumentException werfen — putIfAbsent-CAS.
        // Wenn refund laeuft, wuerde der Status auf REFUNDED kippen.)
        val replay = fx.orchestrator.start(fx.startRequest("k-replay"))
        replay.shouldBeInstanceOf<JobStartHandlerOutcome.AlreadyStarted>()
        replay.jobId shouldBe first.jobId

        val ownerAfter = fx.ownerStore.findById(ownerId)!!
        ownerAfter.status shouldBe QuotaReservationStatus.COMMITTED
        ownerAfter.updatedAt shouldBe firstOwner.updatedAt
    }
})
