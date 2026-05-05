package dev.dmigrate.server.application.job

import dev.dmigrate.server.core.idempotency.IdempotencyKey
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class JobStartServiceTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val principal = Fixtures.principal("alice")
    val tool = "schema_reverse_start"

    fun freshScope(key: String) = IdempotencyScope(
        tenantId = tenant,
        callerId = principal,
        toolName = tool,
        idempotencyKey = IdempotencyKey(key),
    )

    class Fixture(
        val now: Instant = Fixtures.NOW,
        val jobStore: InMemoryJobStore = InMemoryJobStore(),
        val idempotencyStore: InMemoryIdempotencyStore = InMemoryIdempotencyStore(),
        val workerHandleRegistry: InMemoryWorkerHandleRegistry = InMemoryWorkerHandleRegistry(),
        val jobIdSeq: AtomicInteger = AtomicInteger(0),
    ) {
        val transaction = InMemoryJobStartTransaction(jobStore, idempotencyStore)
        val service = JobStartService(
            idempotencyStore = idempotencyStore,
            jobStartTransaction = transaction,
            workerHandleRegistry = workerHandleRegistry,
            jobIdFactory = { "job_${jobIdSeq.incrementAndGet()}" },
        )

        fun jobBuilder(): (String, Instant) -> dev.dmigrate.server.core.job.JobRecord = { jobId, createdAt ->
            Fixtures.jobRecord(jobId).copy(
                managedJob = Fixtures.jobRecord(jobId).managedJob.copy(
                    status = JobStatus.QUEUED,
                    createdAt = createdAt,
                    updatedAt = createdAt,
                ),
            )
        }
    }

    test("Reserved → Started: Job committed, Worker-Handle registriert, Source aus Outcome cancelable") {
        val fx = Fixture()
        val outcome = fx.service.start(
            scope = freshScope("k1"),
            payloadFingerprint = "fp",
            now = fx.now,
            jobBuilder = fx.jobBuilder(),
        )

        outcome.shouldBeInstanceOf<JobStartOutcome.Started>()
        outcome.jobId shouldBe "job_1"
        outcome.record.managedJob.status shouldBe JobStatus.QUEUED

        // Job is in the store.
        fx.jobStore.findById(tenant, "job_1").shouldNotBeNull()

        // Worker-Handle is registered: signaling propagates the reason.
        fx.workerHandleRegistry.signal("job_1", "user-test")
        outcome.cancellationSource.token.isCancellationRequested shouldBe true
        outcome.cancellationSource.token.cancellationReason shouldBe "user-test"
    }

    test("Committed → AlreadyStarted: dedup nach erfolgreichem ersten Start") {
        val fx = Fixture()
        val first = fx.service.start(freshScope("k-dedup"), "fp", fx.now, fx.jobBuilder())
        first.shouldBeInstanceOf<JobStartOutcome.Started>()

        val second = fx.service.start(freshScope("k-dedup"), "fp", fx.now, fx.jobBuilder())
        second.shouldBeInstanceOf<JobStartOutcome.AlreadyStarted>()
        second.jobId shouldBe first.jobId
        // Only one job allocated.
        fx.jobIdSeq.get() shouldBe 1
    }

    test("Conflict: Payload-Fingerprint differs from existing reservation") {
        val fx = Fixture()
        // Manually pre-reserve with a different fingerprint to cause conflict.
        fx.idempotencyStore.reserve(freshScope("k-conf"), "fp-original", fx.now)

        val outcome = fx.service.start(
            scope = freshScope("k-conf"),
            payloadFingerprint = "fp-different",
            now = fx.now,
            jobBuilder = fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.Conflict>()
        outcome.existingFingerprint shouldBe "fp-original"
        // No job allocated for the conflict path.
        fx.jobIdSeq.get() shouldBe 0
    }

    test("ExistingPending: zweiter identischer Reserve-Aufruf wartet auf den ersten") {
        val fx = Fixture()
        // First reserve.
        val firstReserve = fx.idempotencyStore.reserve(freshScope("k-pending"), "fp", fx.now)
        firstReserve.shouldBeInstanceOf<IdempotencyReserveOutcome.Reserved>()

        // Second start with same scope+fingerprint sees ExistingPending.
        val outcome = fx.service.start(
            scope = freshScope("k-pending"),
            payloadFingerprint = "fp",
            now = fx.now,
            jobBuilder = fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.Pending>()
        // No job allocated for Pending — first reserver still owns it.
        fx.jobIdSeq.get() shouldBe 0
    }

    test("Denied: Idempotency-Reserve liefert Denied → Service propagiert Reason") {
        val fx = Fixture()
        val scope = freshScope("k-deny")
        fx.idempotencyStore.reserve(scope, "fp", fx.now)
        fx.idempotencyStore.deny(scope, "policy-denied", fx.now)

        val outcome = fx.service.start(scope, "fp", fx.now, fx.jobBuilder())
        outcome.shouldBeInstanceOf<JobStartOutcome.Denied>()
        outcome.reason shouldBe "policy-denied"
    }

    test("AwaitingApproval: Idempotency-Reserve liefert AwaitingApproval → Service propagiert") {
        val fx = Fixture()
        val scope = freshScope("k-await")
        fx.idempotencyStore.reserve(scope, "fp", fx.now)
        fx.idempotencyStore.markAwaitingApproval(scope, fx.now)

        val outcome = fx.service.start(scope, "fp", fx.now, fx.jobBuilder())
        outcome.shouldBeInstanceOf<JobStartOutcome.AwaitingApproval>()
    }

    test("Custom cancellationSourceFactory ist verkabelbar (test-isolation)") {
        // Sanity: custom factory wird genutzt, nicht der Default. Test-Isolation
        // gegen Worker-Handle-Tests, die spezifische Source-Instanzen wollen.
        val fx = Fixture()
        val customSource = dev.dmigrate.core.cancel.CancellationTokenSource.create()
        val service = JobStartService(
            idempotencyStore = fx.idempotencyStore,
            jobStartTransaction = fx.transaction,
            workerHandleRegistry = fx.workerHandleRegistry,
            jobIdFactory = { "custom-job" },
            cancellationSourceFactory = { customSource },
        )

        val outcome = service.start(
            scope = freshScope("k-custom"),
            payloadFingerprint = "fp",
            now = fx.now,
            jobBuilder = fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.Started>()
        outcome.cancellationSource shouldBeSameInstanceAs customSource
    }

    test("jobBuilder-Exception bricht Service ab und hinterlässt keinen halben Job") {
        // Defensive: jobBuilder läuft NACH der Reserve, VOR der Transaction.
        // Wenn er wirft, ist die Reserve angelegt (PENDING), der Job aber nie
        // committed. Caller kann später retryen — die nächste reserve sieht
        // ExistingPending und liefert Pending zurück.
        val fx = Fixture()
        val scope = freshScope("k-throw")

        shouldThrow<IllegalStateException> {
            fx.service.start(scope, "fp", fx.now, jobBuilder = { _, _ ->
                throw IllegalStateException("builder broke")
            })
        }
        // No job, but a PENDING reservation exists.
        fx.jobStore.findById(tenant, "job_1") shouldBe null
        val retry = fx.idempotencyStore.reserve(scope, "fp", fx.now)
        retry.shouldBeInstanceOf<IdempotencyReserveOutcome.ExistingPending>()
    }
})
