package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.idempotency.IdempotencyKey
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.ports.JobStartTransactionOutcome
import dev.dmigrate.server.ports.contract.Fixtures
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * LF-012 / LN-011 / LN-017 / LN-027 contract test for [InMemoryJobStartTransaction]: belegt,
 * dass `JobStore.save` und `IdempotencyStore.commit` jointly visible
 * werden und keine Saga-Halbzustände entstehen.
 */
class InMemoryJobStartTransactionTest : FunSpec({

    val tenant = Fixtures.tenant("acme")
    val principal = Fixtures.principal("alice")

    fun freshScope(key: String) = IdempotencyScope(
        tenantId = tenant,
        callerId = principal,
        toolName = "schema_reverse_start",
        idempotencyKey = IdempotencyKey(key),
    )

    test("commit makes job and COMMITTED idempotency entry jointly visible") {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val tx = InMemoryJobStartTransaction(jobStore, idempotencyStore)
        val now = Fixtures.NOW

        val scope = freshScope("k1")
        val reserved = idempotencyStore.reserve(scope, "fp", now)
        reserved.shouldBeInstanceOf<IdempotencyReserveOutcome.Reserved>()

        val outcome = tx.commit(
            jobRecord = Fixtures.jobRecord("job_1"),
            idempotencyScope = scope,
            now = now,
        )
        outcome.shouldBeInstanceOf<JobStartTransactionOutcome.Committed>()

        // Both stores see their data.
        jobStore.findById(tenant, "job_1").shouldNotBeNull()
        val idempEntry = idempotencyStore.reserve(scope, "fp", now)
        idempEntry.shouldBeInstanceOf<IdempotencyReserveOutcome.Committed>()
        idempEntry.resultRef shouldBe "job_1"
    }

    test("commit returns IdempotencyNotEligible when the scope is unreserved") {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val tx = InMemoryJobStartTransaction(jobStore, idempotencyStore)

        // Skip reserve — scope has no entry, commit is not eligible.
        val outcome = tx.commit(
            jobRecord = Fixtures.jobRecord("orphan"),
            idempotencyScope = freshScope("never-reserved"),
            now = Fixtures.NOW,
        )
        outcome shouldBe JobStartTransactionOutcome.IdempotencyNotEligible
    }

    test("commit's COMMITTED idempotency retention covers the job's expiresAt") {
        val jobStore = InMemoryJobStore()
        // Default committedRetentionSeconds = 86_400 (24h). Set the job
        // to expire 7 days out so the linkage rule kicks in.
        val idempotencyStore = InMemoryIdempotencyStore(committedRetentionSeconds = 86_400)
        val tx = InMemoryJobStartTransaction(jobStore, idempotencyStore)

        val scope = freshScope("k-far-expiry")
        val now = Fixtures.NOW
        val sevenDaysOut = now.plusSeconds(7 * 86_400)
        idempotencyStore.reserve(scope, "fp", now)

        tx.commit(
            jobRecord = Fixtures.jobRecord("job_long", expiresAt = sevenDaysOut),
            idempotencyScope = scope,
            now = now,
        )

        // The COMMITTED entry should still be reachable at job's expiresAt
        // (i.e. way beyond the store's 24h default).
        val almostExpired = idempotencyStore.reserve(scope, "fp", sevenDaysOut.minusSeconds(60))
        almostExpired.shouldBeInstanceOf<IdempotencyReserveOutcome.Committed>()
    }

    test("parallel commits with different scopes succeed independently") {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val tx = InMemoryJobStartTransaction(jobStore, idempotencyStore)
        val now = Fixtures.NOW

        val scopeA = freshScope("k-A")
        val scopeB = freshScope("k-B")
        idempotencyStore.reserve(scopeA, "fp-A", now)
        idempotencyStore.reserve(scopeB, "fp-B", now)

        val committed = AtomicInteger(0)
        val tA = Thread {
            val o = tx.commit(Fixtures.jobRecord("job_A"), scopeA, now)
            if (o is JobStartTransactionOutcome.Committed) committed.incrementAndGet()
        }
        val tB = Thread {
            val o = tx.commit(Fixtures.jobRecord("job_B"), scopeB, now)
            if (o is JobStartTransactionOutcome.Committed) committed.incrementAndGet()
        }
        tA.start(); tB.start(); tA.join(); tB.join()

        committed.get() shouldBe 2
        jobStore.findById(tenant, "job_A").shouldNotBeNull()
        jobStore.findById(tenant, "job_B").shouldNotBeNull()
    }

    test("commit on already-COMMITTED scope returns IdempotencyNotEligible — second job is NOT saved") {
        val jobStore = InMemoryJobStore()
        val idempotencyStore = InMemoryIdempotencyStore()
        val tx = InMemoryJobStartTransaction(jobStore, idempotencyStore)
        val scope = freshScope("k-double-commit")
        val now = Fixtures.NOW
        idempotencyStore.reserve(scope, "fp", now)

        val first = tx.commit(Fixtures.jobRecord("job_first"), scope, now)
        first.shouldBeInstanceOf<JobStartTransactionOutcome.Committed>()

        val second = tx.commit(Fixtures.jobRecord("job_second"), scope, now)
        second shouldBe JobStartTransactionOutcome.IdempotencyNotEligible

        // LF-012 / LN-011 / LN-017 / LN-027 verbietet "sichtbaren Job ohne Idempotency-Commit".
        // Die InMemory-Implementation committed Idempotency vor dem
        // Save, und der zweite Save findet nicht statt — kein
        // Halbzustand.
        jobStore.findById(tenant, "job_first").shouldNotBeNull()
        jobStore.findById(tenant, "job_second") shouldBe null
    }
})
