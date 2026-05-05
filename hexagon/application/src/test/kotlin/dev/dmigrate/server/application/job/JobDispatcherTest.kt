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
})
