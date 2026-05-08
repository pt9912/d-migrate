package dev.dmigrate.server.integration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.application.fingerprint.DefaultPayloadFingerprintService
import dev.dmigrate.server.application.fingerprint.JsonValue
import dev.dmigrate.server.application.job.ApprovedRetryService
import dev.dmigrate.server.application.job.JobCancelOutcome
import dev.dmigrate.server.application.job.JobCancelService
import dev.dmigrate.server.application.job.JobDispatcher
import dev.dmigrate.server.application.job.JobStartHandlerOutcome
import dev.dmigrate.server.application.job.JobStartOrchestrator
import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.policy.ConfiguredPolicyService
import dev.dmigrate.server.application.policy.PolicyEffect
import dev.dmigrate.server.application.quota.QuotaReservationStatus
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.persistence.jdbc.idempotency.JdbcIdempotencyStore
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.job.JdbcJobStartTransaction
import dev.dmigrate.server.persistence.jdbc.job.JdbcJobStore
import dev.dmigrate.server.persistence.jdbc.migration.JdbcMigrationRunner
import dev.dmigrate.server.persistence.jdbc.quota.JdbcOwnerAwareQuotaService
import dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaReservationOwnerStore
import dev.dmigrate.server.persistence.jdbc.quota.JdbcQuotaStore
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.WorkerHandleRegistry
import dev.dmigrate.server.ports.SignalOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.NamedTag
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.postgresql.PostgreSQLContainer
import dev.dmigrate.core.cancel.CancellationTokenSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

private val IntegrationTag = NamedTag("integration")

private val pipelineTestContainer = PostgreSQLContainer("postgres:16-alpine")
    .withDatabaseName("dmigrate_state")
    .withUsername("dmigrate")
    .withPassword("dmigrate")

private var pipelineTestDataSource: HikariDataSource? = null

/**
 * Phase E2.8 — End-to-End-Akzeptanzpfade gegen das voll-JDBC-gewirte
 * Phase-E-Stack (Plan-Refs: ImpPlan-0.9.6-E2.md § 7 + § 8).
 *
 * Pinned ist die Vertrags-Bruecke zwischen den InMemory-Akzeptanztests
 * (`JobQuotaScenarioTest` etc.) und der echten Postgres-Persistenz: die
 * gleichen Szenarien laufen hier ueber JdbcIdempotencyStore +
 * JdbcJobStore + JdbcJobStartTransaction + JdbcOwnerAwareQuotaService,
 * mit echter DB-Atomicity statt `synchronized`.
 *
 * Tagged `integration` — laeuft nur unter `-PintegrationTests`.
 */
class ServerPipelineE2ETest : FunSpec({

    tags(IntegrationTag)

    val tenant = Fixtures.tenant("acme")
    val principal = Fixtures.principal("alice")
    val now: Instant = Fixtures.NOW
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    beforeSpec {
        pipelineTestContainer.start()
        val cfg = HikariConfig().apply {
            jdbcUrl = pipelineTestContainer.jdbcUrl
            username = pipelineTestContainer.username
            password = pipelineTestContainer.password
            maximumPoolSize = 8
            poolName = "phase-e-pipeline-e2e"
        }
        pipelineTestDataSource = HikariDataSource(cfg)
        JdbcMigrationRunner(pipelineTestDataSource!!).migrate()
    }

    afterSpec {
        pipelineTestDataSource?.close()
        pipelineTestDataSource = null
        pipelineTestContainer.stop()
    }

    fun freshFixture(jobLimit: Long = 1L): Fixture {
        val ds = pipelineTestDataSource!!
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    TRUNCATE
                      idempotency_reservations,
                      init_resume_reservations,
                      jobs,
                      quota_reservation_owners,
                      quota_counters
                    """.trimIndent(),
                )
            }
        }
        return Fixture(ds, tenant, principal, now, clock, jobLimit)
    }

    test("Happy-Path: Start -> Auto-Dispatch -> Worker.Succeeded -> Quota released (Plan §7.9 line 1313)") {
        val fx = freshFixture()
        val outcome = fx.orchestrator.start(fx.startRequest("k1"))
        outcome.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()

        val jobId = outcome.jobId
        // Async-Pool: warten bis der Worker terminiert ist.
        awaitCondition {
            fx.jobStore.findById(tenant, jobId)?.managedJob?.status == JobStatus.SUCCEEDED
        }

        // Owner-Status: COMMITTED (vom Orchestrator) -> RELEASED (vom
        // Dispatcher beim Terminal). Counter zurueck auf 0.
        val ownerId = fx.expectedOwnerId("k1")
        awaitCondition {
            fx.ownerStore.findById(ownerId)?.status == QuotaReservationStatus.RELEASED
        }
        fx.quotaStore.current(fx.activeJobsKey()) shouldBe 0L
    }

    test("RateLimited: zweiter Start ueber Limit -> kein Job, kein Owner-Eintrag") {
        val fx = freshFixture(jobLimit = 1L)
        // Worker blockiert, damit der erste Job RUNNING bleibt und den Slot belegt.
        fx.holdRunning = true
        val first = fx.orchestrator.start(fx.startRequest("k-first"))
        first.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        // Worker sitzt jetzt in der Pause; Owner=COMMITTED, Counter=1.

        val second = fx.orchestrator.start(fx.startRequest("k-second"))
        second.shouldBeInstanceOf<JobStartHandlerOutcome.RateLimited>()

        // Plan §7.9 line 1302-1304: kein jobBuilder, kein JobStore-Eintrag,
        // kein Owner-Eintrag fuer die zweite Reservation.
        fx.jobStore.findById(tenant, "job_2") shouldBe null
        fx.ownerStore.findById(fx.expectedOwnerId("k-second")) shouldBe null
        // jobBuilder darf NIE fuer den ueberbuchten Start gerufen worden sein.
        fx.jobBuilderCalls shouldBe 1

        // Ersten Worker freigeben, damit der Test nicht haengt.
        fx.releaseHold()
    }

    test("Cancel queued: JobCancelService released Quota beim QUEUED -> CANCELLED CAS") {
        val fx = freshFixture(jobLimit = 5L)
        // jobDispatcher NICHT verkabelt -> Job bleibt QUEUED nach commit.
        fx.disableAutoDispatch = true

        val started = fx.orchestrator.start(fx.startRequest("k-queued"))
        started.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        val jobId = started.jobId
        fx.jobStore.findById(tenant, jobId)!!.managedJob.status shouldBe JobStatus.QUEUED
        fx.quotaStore.current(fx.activeJobsKey()) shouldBe 1L

        val cancelOutcome = fx.cancelService.cancel(
            jobIdOrUri = jobId,
            principal = fx.principalContext(),
            reason = "user-cancel",
            now = now.plusSeconds(2),
        )
        cancelOutcome.shouldBeInstanceOf<JobCancelOutcome.Cancelled>()
        fx.jobStore.findById(tenant, jobId)!!.managedJob.status shouldBe JobStatus.CANCELLED
        // Plan §7.9 line 1291-1292: queued-Cancel released Owner + Counter.
        fx.ownerStore.findById(fx.expectedOwnerId("k-queued"))!!.status shouldBe QuotaReservationStatus.RELEASED
        fx.quotaStore.current(fx.activeJobsKey()) shouldBe 0L
    }

    test("Idempotency-Replay: zweiter reserve auf identischen Scope -> AlreadyStarted, kein Counter-Decrement") {
        val fx = freshFixture(jobLimit = 5L)

        val first = fx.orchestrator.start(fx.startRequest("k-replay"))
        first.shouldBeInstanceOf<JobStartHandlerOutcome.Started>()
        val firstJobId = first.jobId
        // Counter: nach Worker-Succeeded released wieder auf 0.
        awaitCondition { fx.quotaStore.current(fx.activeJobsKey()) == 0L }

        // Zweiter Start mit gleichem Scope: Idempotency liefert COMMITTED ->
        // AlreadyStarted. Plan §7.9 line 1312: weder reserve noch refund.
        val replay = fx.orchestrator.start(fx.startRequest("k-replay"))
        replay.shouldBeInstanceOf<JobStartHandlerOutcome.AlreadyStarted>()
        replay.jobId shouldBe firstJobId
        // Counter weiterhin 0 — kein neuer reserve, kein refund.
        fx.quotaStore.current(fx.activeJobsKey()) shouldBe 0L
    }
})

/**
 * Polling-Helper fuer async-Worker-Tests. Wartet bis [predicate] true
 * liefert, mit Timeout. Fail-fast bei Timeout, sonst silently durch.
 */
private fun awaitCondition(
    timeoutMs: Long = 5_000,
    pollMs: Long = 20,
    predicate: () -> Boolean,
) {
    val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000
    while (!predicate() && System.nanoTime() < deadlineNs) {
        Thread.sleep(pollMs)
    }
    if (!predicate()) error("awaitCondition: timed out after ${timeoutMs}ms")
}

/**
 * Vollstaendig JDBC-gewirtes Phase-E-Setup fuer einen E2E-Test. Spiegelt
 * das InMemory-Fixture aus `JobQuotaScenarioTest` 1:1, ersetzt aber alle
 * Stores durch ihre Postgres-Implementationen.
 */
private class Fixture(
    ds: HikariDataSource,
    val tenant: dev.dmigrate.server.core.principal.TenantId,
    val principal: dev.dmigrate.server.core.principal.PrincipalId,
    val now: Instant,
    clock: Clock,
    jobLimit: Long,
) {
    private val runner = JdbcTransactionRunner(ds)
    val idempotencyStore = JdbcIdempotencyStore(runner)
    val jobStore = JdbcJobStore(runner)
    val ownerStore = JdbcQuotaReservationOwnerStore(runner)
    val quotaStore = JdbcQuotaStore(runner)
    val transaction = JdbcJobStartTransaction(runner, idempotencyStore, jobStore)
    val workerHandleRegistry: WorkerHandleRegistry = InMemoryWorkerHandleRegistry()

    val quotaService = JdbcOwnerAwareQuotaService(
        transactionRunner = runner,
        jdbcQuotaStore = quotaStore,
        jdbcOwnerStore = ownerStore,
        limitFor = { jobLimit },
    )

    var disableAutoDispatch: Boolean = false
    var holdRunning: Boolean = false
    private val holdLatch = java.util.concurrent.CountDownLatch(1)
    var jobBuilderCalls: Int = 0
    private val jobIdSeq = AtomicInteger(0)
    private val approvalGrantStore = InMemoryApprovalGrantStore()
    private val grantService = DefaultApprovalGrantService(approvalGrantStore, ApprovalGrantValidator())
    private val approvedRetryService = ApprovedRetryService(
        approvalGrantService = grantService,
        idempotencyStore = idempotencyStore,
        jobStartTransaction = transaction,
        workerHandleRegistry = workerHandleRegistry,
        jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
    )

    private val worker: JobWorker = JobWorker { _, _ ->
        if (holdRunning) {
            // Async-runner: blockiert bis releaseHold() den Latch oeffnet.
            // Im Test-Pfad heisst das: der zweite Reserve sieht den
            // Counter belegt.
            holdLatch.await()
        }
        JobWorkerOutcome.Succeeded()
    }

    private val asyncExecutor: java.util.concurrent.Executor =
        java.util.concurrent.Executors.newFixedThreadPool(2)

    val dispatcher = JobDispatcher(
        jobStore = jobStore,
        executor = asyncExecutor,
        clock = clock,
        quotaService = quotaService,
    )

    val cancelService = JobCancelService(
        jobStore = jobStore,
        workerHandleRegistry = workerHandleRegistry,
        cancelReasonScrubber = { it },
        quotaService = quotaService,
    )

    val orchestrator: JobStartOrchestrator
        get() = JobStartOrchestrator(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            approvalGrantStore = approvalGrantStore,
            approvedRetryService = approvedRetryService,
            policyService = ConfiguredPolicyService(rules = emptyList(), defaultEffect = PolicyEffect.Allow),
            payloadFingerprintService = DefaultPayloadFingerprintService(),
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
            quotaService = quotaService,
            jobDispatcher = if (disableAutoDispatch) null else dispatcher,
            jobWorkerFactory = if (disableAutoDispatch) null else { _, _ -> worker },
            jobStore = jobStore,
        )

    fun startRequest(idempotencyKey: String): JobStartRequest = JobStartRequest(
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

    fun activeJobsKey() = dev.dmigrate.server.ports.quota.QuotaKey(
        tenantId = tenant,
        dimension = dev.dmigrate.server.ports.quota.QuotaDimension.ACTIVE_JOBS,
        principalId = principal,
        operation = "schema_reverse_start",
    )

    fun expectedOwnerId(idempotencyKey: String): String =
        "${tenant.value}:${principal.value}:schema_reverse_start:$idempotencyKey"

    fun principalContext(): PrincipalContext = PrincipalContext(
        principalId = principal,
        homeTenantId = tenant,
        effectiveTenantId = tenant,
        allowedTenantIds = setOf(tenant),
        scopes = setOf("dmigrate:admin"),
        isAdmin = true,
        auditSubject = principal.value,
        authSource = dev.dmigrate.server.core.principal.AuthSource.LOCAL,
        expiresAt = now.plusSeconds(3_600),
    )

    fun releaseHold() {
        holdRunning = false
        holdLatch.countDown()
    }
}
