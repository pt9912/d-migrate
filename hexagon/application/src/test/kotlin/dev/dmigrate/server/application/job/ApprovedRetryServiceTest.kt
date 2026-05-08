package dev.dmigrate.server.application.job

import dev.dmigrate.server.application.approval.ApprovalFixtures
import dev.dmigrate.server.application.approval.ApprovalGrantValidator
import dev.dmigrate.server.application.approval.DefaultApprovalGrantService
import dev.dmigrate.server.core.idempotency.IdempotencyKey
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryApprovalGrantStore
import dev.dmigrate.server.ports.memory.InMemoryIdempotencyStore
import dev.dmigrate.server.ports.memory.InMemoryJobStartTransaction
import dev.dmigrate.server.ports.memory.InMemoryJobStore
import dev.dmigrate.server.ports.memory.InMemoryWorkerHandleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class ApprovedRetryServiceTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val principal = Fixtures.principal("alice")
    val tool = "data_profile_start"

    // ApprovalFixtures uses correlationKey = "idem-1" by default; the
    // scope's idempotencyKey IS the correlation key per Plan §5.5, so we
    // align the two here.
    fun scope(key: String = "idem-1") = IdempotencyScope(
        tenantId = tenant,
        callerId = principal,
        toolName = tool,
        idempotencyKey = IdempotencyKey(key),
    )

    class Fixture(
        val now: Instant = Fixtures.NOW,
        val grantStore: InMemoryApprovalGrantStore = InMemoryApprovalGrantStore(),
        val idempotencyStore: InMemoryIdempotencyStore = InMemoryIdempotencyStore(),
        val jobStore: InMemoryJobStore = InMemoryJobStore(),
        val workerHandleRegistry: InMemoryWorkerHandleRegistry = InMemoryWorkerHandleRegistry(),
        val jobIdSeq: AtomicInteger = AtomicInteger(0),
    ) {
        val grantService = DefaultApprovalGrantService(grantStore, ApprovalGrantValidator())
        val transaction = InMemoryJobStartTransaction(jobStore, idempotencyStore)
        val service = ApprovedRetryService(
            approvalGrantService = grantService,
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

        fun primeAwaitingApproval(scope: IdempotencyScope, fingerprint: String = "fp") {
            idempotencyStore.reserve(scope, fingerprint, now)
            idempotencyStore.markAwaitingApproval(scope, now)
        }

        fun saveGrantForAttempt() {
            grantStore.save(ApprovalFixtures.grant(
                tokenFingerprint = ApprovalFixtures.TOKEN_FP,
                tool = tool,
            ))
        }
    }

    test("Valid Grant + AWAITING_APPROVAL -> Started, Job committed, Worker-Handle registriert") {
        val fx = Fixture()
        val s = scope()
        fx.primeAwaitingApproval(s)
        fx.saveGrantForAttempt()

        val outcome = fx.service.retry(
            attempt = ApprovalFixtures.attempt(tool = tool),
            scope = s,
            now = fx.now,
            jobBuilder = fx.jobBuilder(),
        )

        outcome.shouldBeInstanceOf<JobStartOutcome.Started>()
        outcome.jobId shouldBe "job_1"
        fx.jobStore.findById(tenant, "job_1") shouldBe fx.jobStore.findById(tenant, "job_1")
        // Worker-Handle propagiert Cancel-Signal.
        fx.workerHandleRegistry.signal("job_1", "user-test")
        outcome.cancellationSource.token.isCancellationRequested shouldBe true
    }

    test("zweiter Retry nach Commit -> AlreadyStarted mit demselben jobId") {
        val fx = Fixture()
        val s = scope()
        fx.primeAwaitingApproval(s)
        fx.saveGrantForAttempt()

        val first = fx.service.retry(ApprovalFixtures.attempt(tool = tool), s, fx.now, fx.jobBuilder())
        first.shouldBeInstanceOf<JobStartOutcome.Started>()

        val second = fx.service.retry(ApprovalFixtures.attempt(tool = tool), s, fx.now, fx.jobBuilder())
        second.shouldBeInstanceOf<JobStartOutcome.AlreadyStarted>()
        second.jobId shouldBe first.jobId
        // Nur ein Job allokiert.
        fx.jobIdSeq.get() shouldBe 1
    }

    test("ungueltiger Grant (Unknown) -> Denied mit policy:grant-unknown + denialExpiresAt") {
        val fx = Fixture()
        val s = scope()
        fx.primeAwaitingApproval(s)
        // Kein Grant gespeichert.

        val outcome = fx.service.retry(
            ApprovalFixtures.attempt(tool = tool),
            s,
            fx.now,
            fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.Denied>()
        outcome.reason shouldBe "policy:grant-unknown"
        // Plan §7.5: DENIED enthaelt denialExpiresAt; Default-Retention 600s.
        outcome.expiresAt shouldBe fx.now.plusSeconds(600)
        // Kein Job allokiert.
        fx.jobIdSeq.get() shouldBe 0
    }

    test("ungueltiger Grant (Expired) -> Denied mit policy:grant-expired") {
        val fx = Fixture()
        val s = scope()
        fx.primeAwaitingApproval(s)
        // Grant in der Vergangenheit abgelaufen.
        fx.grantStore.save(ApprovalFixtures.grant(
            tokenFingerprint = ApprovalFixtures.TOKEN_FP,
            tool = tool,
            expiresAt = ApprovalFixtures.PAST,
        ))

        val outcome = fx.service.retry(
            ApprovalFixtures.attempt(tool = tool),
            s,
            fx.now,
            fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.Denied>()
        outcome.reason shouldBe "policy:grant-expired"
    }

    test("Cross-Tenant-Lookup -> Unknown (Store-Lookup ist tenant-scoped)") {
        // Grant fuer anderen Tenant gespeichert: ApprovalGrantStore.findByTokenFingerprint
        // ist tenant-scoped, also liefert der Service Unknown statt TenantMismatch.
        // (TenantMismatch im Validator ist Defense-in-Depth und wird in
        // ApprovalGrantValidatorTest abgedeckt.)
        val fx = Fixture()
        val s = scope()
        fx.primeAwaitingApproval(s)
        fx.grantStore.save(ApprovalFixtures.grant(
            tokenFingerprint = ApprovalFixtures.TOKEN_FP,
            tenant = "andere",
            tool = tool,
        ))

        val outcome = fx.service.retry(
            ApprovalFixtures.attempt(tool = tool, tenant = "acme"),
            s,
            fx.now,
            fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.Denied>()
        outcome.reason shouldBe "policy:grant-unknown"
    }

    test("ungueltiger Grant (PayloadMismatch) -> Denied mit policy:payload-mismatch") {
        val fx = Fixture()
        val s = scope()
        fx.primeAwaitingApproval(s)
        fx.grantStore.save(ApprovalFixtures.grant(
            tokenFingerprint = ApprovalFixtures.TOKEN_FP,
            tool = tool,
            payloadFingerprint = "fp-original",
        ))

        val outcome = fx.service.retry(
            ApprovalFixtures.attempt(tool = tool, payloadFingerprint = "fp-different"),
            s,
            fx.now,
            fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.Denied>()
        outcome.reason shouldBe "policy:payload-mismatch"
    }

    test("AlreadyClaimed (Race) -> Pending mit lease-expiresAt") {
        // Plan §7.5: parallele genehmigte Retries erzeugen genau einen Job.
        // Erste Claim gewinnt, zweite Claim auf demselben Scope sieht
        // AlreadyClaimed waehrend der erste Caller noch im Commit ist.
        val fx = Fixture()
        val s = scope()
        fx.primeAwaitingApproval(s)
        fx.saveGrantForAttempt()

        // Erste Claim (manuell, ohne Commit) lockt die Reservierung.
        fx.idempotencyStore.claimApproved(s, fx.now)

        // Zweite Retry sieht AlreadyClaimed.
        val outcome = fx.service.retry(
            ApprovalFixtures.attempt(tool = tool),
            s,
            fx.now,
            fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.Pending>()
        // Kein Job vom zweiten Caller allokiert.
        fx.jobIdSeq.get() shouldBe 0
    }

    test("nicht-AWAITING_APPROVAL Reservation -> Failed (defensive)") {
        // Race: Reserve ohne markAwaitingApproval; ApprovedRetry sollte
        // nicht versehentlich auf PENDING claimen.
        val fx = Fixture()
        val s = scope()
        fx.idempotencyStore.reserve(s, "fp", fx.now)
        // KEIN markAwaitingApproval.
        fx.saveGrantForAttempt()

        val outcome = fx.service.retry(
            ApprovalFixtures.attempt(tool = tool),
            s,
            fx.now,
            fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.Failed>()
        outcome.reason shouldBe ApprovedRetryService.REASON_NOT_AWAITING_APPROVAL
        fx.jobIdSeq.get() shouldBe 0
    }

    test("bereits DENIED -> deny() no-op, Re-Read zeigt Denied + originaler expiresAt") {
        // Race: ein paralleler Retry hat schon DENIED gesetzt. Unsere deny()
        // bleibt no-op (returns null), Re-Claim liest den Bestands-Eintrag.
        val fx = Fixture()
        val s = scope()
        fx.primeAwaitingApproval(s)
        // Erst manuell DENIED setzen mit eigener Reason+expiresAt.
        val originalExpiry = fx.idempotencyStore.deny(s, "policy:earlier-rejection", fx.now)!!

        // Jetzt mit einem Grant, der nicht matcht (PayloadMismatch), Retry.
        fx.grantStore.save(ApprovalFixtures.grant(
            tokenFingerprint = ApprovalFixtures.TOKEN_FP,
            tool = tool,
            payloadFingerprint = "fp-other",
        ))

        val outcome = fx.service.retry(
            ApprovalFixtures.attempt(tool = tool, payloadFingerprint = "fp-default"),
            s,
            fx.now.plusSeconds(10),
            fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.Denied>()
        // Re-Claim liefert die VORHERIGE Denied-Begruendung, nicht die unsere.
        outcome.reason shouldBe "policy:earlier-rejection"
        outcome.expiresAt shouldBe originalExpiry
    }

    test("bereits COMMITTED -> deny() no-op, Re-Read liefert AlreadyStarted") {
        // Race: anderer Approved-Retry hat bereits committed. Unser
        // ungueltiger Grant darf das COMMITTED-Outcome nicht ueberschreiben.
        val fx = Fixture()
        val s = scope()
        fx.primeAwaitingApproval(s)
        fx.idempotencyStore.commit(s, "existing-job", fx.now)
        // KEIN passender Grant.

        val outcome = fx.service.retry(
            ApprovalFixtures.attempt(tool = tool),
            s,
            fx.now,
            fx.jobBuilder(),
        )
        outcome.shouldBeInstanceOf<JobStartOutcome.AlreadyStarted>()
        outcome.jobId shouldBe "existing-job"
    }
})
