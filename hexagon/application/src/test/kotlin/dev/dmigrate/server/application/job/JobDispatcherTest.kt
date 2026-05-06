package dev.dmigrate.server.application.job

import dev.dmigrate.core.cancel.CancellationToken
import dev.dmigrate.core.cancel.OperationCancelSource
import dev.dmigrate.core.cancel.OperationCancelledException
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.ports.JobWorker
import dev.dmigrate.server.ports.JobWorkerOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors

class JobDispatcherTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val now: Instant = Instant.parse("2026-05-05T12:00:00Z")
    val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    fun seedQueued(jobId: String = "j1", store: InMemoryJobStore = InMemoryJobStore()): InMemoryJobStore {
        store.save(
            Fixtures.jobRecord(jobId).copy(
                managedJob = Fixtures.jobRecord(jobId).managedJob.copy(
                    status = JobStatus.QUEUED,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        return store
    }

    test("Succeeded → RUNNING → SUCCEEDED, artifacts persistiert") {
        val store = seedQueued()
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j1")!!
        val worker = JobWorker { _, _ -> JobWorkerOutcome.Succeeded(listOf("dmigrate://tenants/acme/artifacts/a1")) }

        val outcome = dispatcher.dispatch(record, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>()
        outcome.artifactRefs shouldBe listOf("dmigrate://tenants/acme/artifacts/a1")

        val final = store.findById(tenant, "j1")!!
        final.managedJob.status shouldBe JobStatus.SUCCEEDED
        final.managedJob.artifacts shouldBe listOf("dmigrate://tenants/acme/artifacts/a1")
    }

    test("Cancelled (worker liefert) → RUNNING → CANCELLED, signalAcked=true") {
        val store = seedQueued()
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j1")!!
        val worker = JobWorker { _, _ -> JobWorkerOutcome.Cancelled(reason = "user-cancel") }

        val outcome = dispatcher.dispatch(record, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Cancelled>()
        outcome.reason shouldBe "user-cancel"

        val final = store.findById(tenant, "j1")!!
        final.managedJob.status shouldBe JobStatus.CANCELLED
        final.managedJob.cancelRequest.signalAcked shouldBe true
        final.managedJob.cancelRequest.ackedAt.shouldNotBeNull()
    }

    test("OperationCancelledException JOB_CANCEL → Cancelled-Outcome (Default-Source)") {
        val store = seedQueued()
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j1")!!
        val worker = JobWorker { _, _ -> throw OperationCancelledException(reason = "from-token") }

        val outcome = dispatcher.dispatch(record, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Cancelled>()
        outcome.reason shouldBe "from-token"
        store.findById(tenant, "j1")!!.managedJob.status shouldBe JobStatus.CANCELLED
    }

    test("OperationCancelledException RUNNER_TIMEOUT → Failed(OPERATION_TIMEOUT), kein Cancelled") {
        // Plan §7.7: RUNNER_TIMEOUT-Source darf NICHT als generischer
        // Cancel gemapped werden; Job-Status wird FAILED mit
        // error.code=OPERATION_TIMEOUT.
        val store = seedQueued()
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j1")!!
        val worker = JobWorker { _, _ ->
            throw OperationCancelledException(
                reason = "runner budget exhausted",
                source = OperationCancelSource.RUNNER_TIMEOUT,
            )
        }

        val outcome = dispatcher.dispatch(record, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
        outcome.errorCode shouldBe JobDispatcher.ERROR_CODE_OPERATION_TIMEOUT
        outcome.errorMessage shouldBe "runner budget exhausted"

        val final = store.findById(tenant, "j1")!!
        final.managedJob.status shouldBe JobStatus.FAILED
        final.managedJob.error?.code shouldBe "OPERATION_TIMEOUT"
        // Defensive: kein CANCELLED, kein signalAcked-Bookkeeping.
        final.managedJob.cancelRequest.signalAcked shouldBe false
    }

    test("OperationCancelledException explizit JOB_CANCEL → Cancelled (gleich wie Default)") {
        val store = seedQueued()
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j1")!!
        val worker = JobWorker { _, _ ->
            throw OperationCancelledException(
                reason = "user-cancel",
                source = OperationCancelSource.JOB_CANCEL,
            )
        }

        val outcome = dispatcher.dispatch(record, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Cancelled>()
        outcome.reason shouldBe "user-cancel"
    }

    test("Cancel-Reason wird SCRUBBED in cancelRequest.requestedReason projiziert (Plan §7.7)") {
        val store = seedQueued()
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j1")!!
        val worker = JobWorker { _, _ ->
            JobWorkerOutcome.Cancelled(reason = "leak Bearer abc.def.ghi token")
        }

        dispatcher.dispatch(record, worker, CancellationToken.none()).get()

        val final = store.findById(tenant, "j1")!!
        final.managedJob.status shouldBe JobStatus.CANCELLED
        // Bearer-Token wurde redigiert.
        final.managedJob.cancelRequest.requestedReason shouldBe "leak Bearer *** token"
    }

    test("Cancel-Reason ueberschreibt NICHT einen schon durabel gesetzten requestedReason (Plan §7.2)") {
        val store = InMemoryJobStore()
        store.save(
            Fixtures.jobRecord("j-pre-cancelled").copy(
                managedJob = Fixtures.jobRecord("j-pre-cancelled").managedJob.copy(
                    status = JobStatus.QUEUED,
                    cancelRequest = dev.dmigrate.server.core.job.JobCancelRequest(
                        requested = true,
                        requestedAt = now,
                        requestedBy = "alice",
                        requestedReason = "first-reason-from-job-cancel-tool",
                        signalSource = "job_cancel",
                    ),
                ),
            ),
        )
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j-pre-cancelled")!!
        val worker = JobWorker { _, _ ->
            JobWorkerOutcome.Cancelled(reason = "later-worker-reason")
        }

        dispatcher.dispatch(record, worker, CancellationToken.none()).get()

        val final = store.findById(tenant, "j-pre-cancelled")!!
        // Der ERSTE Reason gewinnt — Worker-Reason ueberschreibt nicht.
        final.managedJob.cancelRequest.requestedReason shouldBe "first-reason-from-job-cancel-tool"
    }

    test("Custom scrubber wird angewandt") {
        val store = seedQueued()
        val dispatcher = JobDispatcher(
            store,
            clock = clock,
            cancelReasonScrubber = { it.uppercase() },
        )
        val record = store.findById(tenant, "j1")!!
        val worker = JobWorker { _, _ -> JobWorkerOutcome.Cancelled(reason = "shouted") }
        dispatcher.dispatch(record, worker, CancellationToken.none()).get()
        store.findById(tenant, "j1")!!.managedJob.cancelRequest.requestedReason shouldBe "SHOUTED"
    }

    test("Failed → RUNNING → FAILED, error im Record") {
        val store = seedQueued()
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j1")!!
        val worker = JobWorker { _, _ ->
            JobWorkerOutcome.Failed(errorCode = "DRIVER_ERROR", errorMessage = "boom", exitCode = 4)
        }

        val outcome = dispatcher.dispatch(record, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()

        val final = store.findById(tenant, "j1")!!
        final.managedJob.status shouldBe JobStatus.FAILED
        final.managedJob.error?.code shouldBe "DRIVER_ERROR"
        final.managedJob.error?.exitCode shouldBe 4
    }

    test("Worker wirft generische Exception → RUNNER_ERROR Failed") {
        val store = seedQueued()
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j1")!!
        val worker = JobWorker { _, _ -> throw IllegalStateException("oops") }

        val outcome = dispatcher.dispatch(record, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
        outcome.errorCode shouldBe JobDispatcher.REASON_RUNNER_ERROR
        outcome.errorMessage shouldBe "oops"
        store.findById(tenant, "j1")!!.managedJob.status shouldBe JobStatus.FAILED
    }

    test("Job nicht in QUEUED → DISPATCH_RACE, Worker wird nicht ausgefuehrt") {
        val store = InMemoryJobStore()
        // Direkt in RUNNING-Status committed.
        store.save(
            Fixtures.jobRecord("j1").copy(
                managedJob = Fixtures.jobRecord("j1").managedJob.copy(
                    status = JobStatus.RUNNING,
                ),
            ),
        )
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j1")!!
        var workerInvoked = false
        val worker = JobWorker { _, _ ->
            workerInvoked = true
            JobWorkerOutcome.Succeeded()
        }

        val outcome = dispatcher.dispatch(record, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
        outcome.errorCode shouldBe JobDispatcher.REASON_DISPATCH_RACE
        workerInvoked shouldBe false
    }

    test("Job nie persistiert (Caller haelt Record, Store leer) → DISPATCH_NOT_FOUND") {
        val store = InMemoryJobStore()
        val phantomRecord = Fixtures.jobRecord("j-phantom").copy(
            managedJob = Fixtures.jobRecord("j-phantom").managedJob.copy(status = JobStatus.QUEUED),
        )
        val dispatcher = JobDispatcher(store, clock = clock)

        val outcome = dispatcher.dispatch(
            phantomRecord,
            JobWorker { _, _ -> JobWorkerOutcome.Succeeded() },
            CancellationToken.none(),
        ).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Failed>()
        outcome.errorCode shouldBe JobDispatcher.REASON_DISPATCH_NOT_FOUND
    }

    // ── Phase E3 § 3.6 + § 6.3: cancel-while-queued ──

    test("Cancel-while-queued: Job auf CANCELLED → kein Worker-Aufruf, kein applyTerminal-Overwrite") {
        // Plan E3 § 3.6: JobCancelService.cancelQueuedJob hat den Job schon
        // terminalisiert (status=CANCELLED, signalAcked=true, ackedAt,
        // requestedReason). Der Dispatcher sieht IllegalTransition(CANCELLED)
        // und skippt den Worker, OHNE die Cancel-Metadaten zu ueberschreiben.
        val acked = now.plusSeconds(2)
        val cancelledRecord = Fixtures.jobRecord("j-cancelled").copy(
            managedJob = Fixtures.jobRecord("j-cancelled").managedJob.copy(
                status = JobStatus.CANCELLED,
                createdAt = now,
                updatedAt = acked,
                cancelRequest = dev.dmigrate.server.core.job.JobCancelRequest(
                    requested = true,
                    signalAcked = true,
                    requestedAt = now.plusSeconds(1),
                    requestedBy = "alice",
                    requestedReason = "user-cancel",
                    signalSource = "mcp:job_cancel",
                    ackedAt = acked,
                ),
            ),
        )
        val store = InMemoryJobStore().apply { save(cancelledRecord) }
        val dispatcher = JobDispatcher(store, clock = clock)

        var workerInvoked = false
        val worker = JobWorker { _, _ ->
            workerInvoked = true
            JobWorkerOutcome.Succeeded()
        }

        val outcome = dispatcher.dispatch(cancelledRecord, worker, CancellationToken.none()).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Cancelled>()
        outcome.reason shouldBe JobDispatcher.REASON_GENERIC_CANCEL
        workerInvoked shouldBe false

        // Cancel-Metadaten unveraendert — applyTerminal wurde NICHT gerufen.
        val final = store.findById(tenant, "j-cancelled")!!.managedJob
        final.status shouldBe JobStatus.CANCELLED
        final.cancelRequest.signalAcked shouldBe true
        final.cancelRequest.ackedAt shouldBe acked
        final.cancelRequest.requestedReason shouldBe "user-cancel"
        final.cancelRequest.requestedBy shouldBe "alice"
    }

    test("Cancel-while-queued: kein Doppel-Quota-Release durch dispatcher") {
        // Plan E3 § 3.6 + § 7.9 line 1291-1292: queued-cancel released
        // Quota direkt im JobCancelService. Der Dispatcher darf NICHT
        // erneut releasen, sonst entsteht ein Doppel-Decrement.
        val cancelledRecord = Fixtures.jobRecord("j-q").copy(
            quotaReservationOwnerId = "owner-q",
            managedJob = Fixtures.jobRecord("j-q").managedJob.copy(
                status = JobStatus.CANCELLED,
                cancelRequest = dev.dmigrate.server.core.job.JobCancelRequest(
                    requested = true, signalAcked = true,
                ),
            ),
        )
        val store = InMemoryJobStore().apply { save(cancelledRecord) }
        val countingQuota = CountingQuotaService()
        val dispatcher = JobDispatcher(
            jobStore = store,
            clock = clock,
            quotaService = countingQuota,
        )

        dispatcher.dispatch(
            cancelledRecord,
            JobWorker { _, _ -> error("worker must not run") },
            CancellationToken.none(),
        ).get()

        countingQuota.releaseCount.get() shouldBe 0
    }

    test("Async Executor: dispatch laeuft im Worker-Thread, future erfuellt sich asynchron") {
        val store = seedQueued()
        val pool = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = JobDispatcher(store, executor = pool, clock = clock)
            val record = store.findById(tenant, "j1")!!
            val callerThread = Thread.currentThread().id
            var workerThread = -1L
            val gate = java.util.concurrent.CountDownLatch(1)
            val worker = JobWorker { _, _ ->
                workerThread = Thread.currentThread().id
                gate.await()
                JobWorkerOutcome.Succeeded()
            }
            val future: CompletableFuture<JobWorkerOutcome> = dispatcher.dispatch(record, worker, CancellationToken.none())
            future.isDone shouldBe false
            gate.countDown()
            future.get()
            (workerThread == callerThread) shouldBe false
        } finally {
            pool.shutdown()
        }
    }

    test("SyncExecutor laeuft im Caller-Thread") {
        val callerThread = Thread.currentThread().id
        var executorThread = -1L
        val executor = Executor { command ->
            // Vor SyncExecutor-Default eine Wrapper-Recording-Variante.
            executorThread = Thread.currentThread().id
            command.run()
        }
        val store = seedQueued()
        val dispatcher = JobDispatcher(store, executor = executor, clock = clock)
        val record = store.findById(tenant, "j1")!!
        dispatcher.dispatch(record, JobWorker { _, _ -> JobWorkerOutcome.Succeeded() }, CancellationToken.none()).get()
        executorThread shouldBe callerThread
    }

    // ── Phase E3 § 3.5 + § 6.2: Permit-Release im Dispatcher-finally ─

    test("dispatch(...,permit) schliesst Permit nach Worker-Erfolg") {
        val store = seedQueued("j-permit-ok")
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j-permit-ok")!!
        val closeCount = java.util.concurrent.atomic.AtomicInteger(0)
        val permit = JobDispatchPermit { closeCount.incrementAndGet() }

        dispatcher.dispatch(
            record = record,
            worker = JobWorker { _, _ -> JobWorkerOutcome.Succeeded() },
            token = CancellationToken.none(),
            permit = permit,
        ).get()

        closeCount.get() shouldBe 1
    }

    test("dispatch(...,permit) schliesst Permit auch wenn Worker wirft") {
        val store = seedQueued("j-permit-throw")
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j-permit-throw")!!
        val closeCount = java.util.concurrent.atomic.AtomicInteger(0)
        val permit = JobDispatchPermit { closeCount.incrementAndGet() }

        // Worker wirft generische Exception → RUNNER_ERROR-Pfad; Permit
        // muss trotzdem im finally geschlossen werden.
        dispatcher.dispatch(
            record = record,
            worker = JobWorker { _, _ -> error("worker fail") },
            token = CancellationToken.none(),
            permit = permit,
        ).get()

        closeCount.get() shouldBe 1
    }

    // ── Phase E3 § 3.7 (E3.6): Observability-Log-Events ─────────────

    test("dispatch emittiert scheduled + started + finished mit Plan-§-3.7-Feldern") {
        val store = seedQueued("j-obs")
        val dispatcher = JobDispatcher(
            jobStore = store,
            clock = clock,
            executorStatusSnapshot = { JobExecutorStatus(active = 0, queued = 7, completed = 0, rejected = 0, capacity = 32) },
        )
        val record = store.findById(tenant, "j-obs")!!

        val capture = LogbackCapture.during {
            dispatcher.dispatch(
                record = record,
                worker = JobWorker { _, _ -> JobWorkerOutcome.Succeeded() },
                token = CancellationToken.none(),
            ).get()
        }
        val lines = capture.events
            .filter { it.formattedMessage.startsWith("job.dispatch.") }
            .map { it.formattedMessage }
        // Reihenfolge: scheduled -> started -> finished, alle drei Events.
        lines.size shouldBe 3
        lines[0] shouldStartWith "job.dispatch.scheduled jobId=j-obs"
        lines[0] shouldContain "tenant=acme"
        lines[0] shouldContain "tool=data.export"
        lines[0] shouldContain "queueDepth=7"
        lines[1] shouldStartWith "job.dispatch.started jobId=j-obs"
        lines[1] shouldContain "waitMs="
        lines[2] shouldStartWith "job.dispatch.finished jobId=j-obs"
        lines[2] shouldContain "status=SUCCEEDED"
        lines[2] shouldContain "durationMs="
    }

    test("Failed-Outcome: finished-Event enthaelt errorCode") {
        val store = seedQueued("j-fail")
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j-fail")!!
        val capture = LogbackCapture.during {
            dispatcher.dispatch(
                record = record,
                worker = JobWorker { _, _ -> JobWorkerOutcome.Failed("DB_TIMEOUT", "boom") },
                token = CancellationToken.none(),
            ).get()
        }
        val finished = capture.events.first { it.formattedMessage.startsWith("job.dispatch.finished") }
        finished.formattedMessage shouldContain "status=FAILED"
        finished.formattedMessage shouldContain "errorCode=DB_TIMEOUT"
    }

    test("Cancel-while-queued: scheduled + finished, kein started-Event") {
        // Seed direkt als CANCELLED (analog E3.4-Test).
        val cancelledRecord = Fixtures.jobRecord("j-cancelled-obs").copy(
            managedJob = Fixtures.jobRecord("j-cancelled-obs").managedJob.copy(
                status = JobStatus.CANCELLED,
                cancelRequest = dev.dmigrate.server.core.job.JobCancelRequest(
                    requested = true, signalAcked = true,
                ),
            ),
        )
        val store = InMemoryJobStore().apply { save(cancelledRecord) }
        val dispatcher = JobDispatcher(store, clock = clock)
        val capture = LogbackCapture.during {
            dispatcher.dispatch(
                record = cancelledRecord,
                worker = JobWorker { _, _ -> error("worker must not run") },
                token = CancellationToken.none(),
            ).get()
        }
        val events = capture.events.filter { it.formattedMessage.startsWith("job.dispatch.") }
        events.map { it.formattedMessage.substringBefore(" ") } shouldBe listOf(
            "job.dispatch.scheduled",
            "job.dispatch.finished",
        )
        events[1].formattedMessage shouldContain "status=CANCELLED"
    }

    test("dispatch ohne permit (Default null) ist Bestands-Verhalten") {
        val store = seedQueued("j-permit-default")
        val dispatcher = JobDispatcher(store, clock = clock)
        val record = store.findById(tenant, "j-permit-default")!!

        // Default-permit-Aufruf darf nicht werfen — keine Permit-
        // Operation, kein Cleanup-Pfad.
        val outcome = dispatcher.dispatch(
            record = record,
            worker = JobWorker { _, _ -> JobWorkerOutcome.Succeeded() },
            token = CancellationToken.none(),
        ).get()
        outcome.shouldBeInstanceOf<JobWorkerOutcome.Succeeded>()
    }
})

private class CountingQuotaService : dev.dmigrate.server.application.quota.OwnerAwareQuotaService(
    delegate = object : dev.dmigrate.server.application.quota.QuotaService {
        override fun reserve(key: dev.dmigrate.server.ports.quota.QuotaKey, amount: Long) =
            error("not used by cancel-while-queued test")
        override fun commit(reservation: dev.dmigrate.server.application.quota.QuotaReservation) {}
        override fun release(reservation: dev.dmigrate.server.application.quota.QuotaReservation) {}
        override fun refund(reservation: dev.dmigrate.server.application.quota.QuotaReservation) {}
    },
    ownerStore = dev.dmigrate.server.application.quota.InMemoryQuotaReservationOwnerStore(),
) {
    val releaseCount: java.util.concurrent.atomic.AtomicInteger =
        java.util.concurrent.atomic.AtomicInteger(0)

    override fun releaseForOwner(ownerId: String, now: Instant) {
        releaseCount.incrementAndGet()
        super.releaseForOwner(ownerId, now)
    }
}
