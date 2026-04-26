package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyKey
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.ports.IdempotencyStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class IdempotencyStoreContractTests(factory: () -> IdempotencyStore) : FunSpec({

    fun scope(key: String = "k1") = IdempotencyScope(
        tenantId = Fixtures.tenant("acme"),
        callerId = Fixtures.principal("alice"),
        toolName = "data_export_start",
        idempotencyKey = IdempotencyKey(key),
    )

    test("first reserve returns Reserved with PENDING lease") {
        val store = factory()
        val outcome = store.reserve(scope(), "fp", Fixtures.NOW)
        outcome.shouldBeInstanceOf<IdempotencyReserveOutcome.Reserved>()
        outcome.leaseExpiresAt.isAfter(Fixtures.NOW) shouldBe true
    }

    test("second identical reserve returns ExistingPending while lease holds") {
        val store = factory()
        val s = scope()
        store.reserve(s, "fp", Fixtures.NOW)
        val second = store.reserve(s, "fp", Fixtures.NOW.plusSeconds(1))
        second.shouldBeInstanceOf<IdempotencyReserveOutcome.ExistingPending>()
    }

    test("expired PENDING lease with same fingerprint is recovered") {
        val store = factory()
        val s = scope()
        val first = store.reserve(s, "fp", Fixtures.NOW) as IdempotencyReserveOutcome.Reserved
        val recovered = store.reserve(s, "fp", first.leaseExpiresAt.plusSeconds(1))
        recovered.shouldBeInstanceOf<IdempotencyReserveOutcome.Reserved>()
    }

    test("different fingerprint yields Conflict") {
        val store = factory()
        val s = scope()
        store.reserve(s, "fp", Fixtures.NOW)
        val outcome = store.reserve(s, "other", Fixtures.NOW.plusSeconds(1))
        outcome.shouldBeInstanceOf<IdempotencyReserveOutcome.Conflict>()
        outcome.existingFingerprint shouldBe "fp"
    }

    test("markAwaitingApproval transitions PENDING and reserve returns AwaitingApproval") {
        val store = factory()
        val s = scope()
        store.reserve(s, "fp", Fixtures.NOW)
        store.markAwaitingApproval(s, Fixtures.NOW.plusSeconds(1)) shouldBe true
        val again = store.reserve(s, "fp", Fixtures.NOW.plusSeconds(2))
        again.shouldBeInstanceOf<IdempotencyReserveOutcome.AwaitingApproval>()
    }

    test("commit transitions to COMMITTED and reserve returns Committed with resultRef") {
        val store = factory()
        val s = scope()
        store.reserve(s, "fp", Fixtures.NOW)
        store.commit(s, "job_42", Fixtures.NOW.plusSeconds(2)) shouldBe true
        val again = store.reserve(s, "fp", Fixtures.NOW.plusSeconds(3))
        again.shouldBeInstanceOf<IdempotencyReserveOutcome.Committed>()
        again.resultRef shouldBe "job_42"
    }

    test("deny transitions to DENIED and reserve returns Denied with reason") {
        val store = factory()
        val s = scope()
        store.reserve(s, "fp", Fixtures.NOW)
        store.deny(s, "policy violation", Fixtures.NOW.plusSeconds(2)) shouldBe true
        val again = store.reserve(s, "fp", Fixtures.NOW.plusSeconds(3))
        again.shouldBeInstanceOf<IdempotencyReserveOutcome.Denied>()
        again.reason shouldBe "policy violation"
    }

    test("commit and deny are no-ops when entry already terminal") {
        val store = factory()
        val s = scope()
        store.reserve(s, "fp", Fixtures.NOW)
        store.commit(s, "ref", Fixtures.NOW.plusSeconds(1)) shouldBe true
        store.commit(s, "ref2", Fixtures.NOW.plusSeconds(2)) shouldBe false
        store.deny(s, "n/a", Fixtures.NOW.plusSeconds(3)) shouldBe false
    }

    test("parallel identical reserves yield exactly one Reserved") {
        val store = factory()
        val s = scope()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(16) { Callable { store.reserve(s, "fp", Fixtures.NOW) } }
            val results = pool.invokeAll(tasks).map { it.get() }
            val reservedCount = results.count { it is IdempotencyReserveOutcome.Reserved }
            val existingCount = results.count { it is IdempotencyReserveOutcome.ExistingPending }
            reservedCount shouldBe 1
            existingCount shouldBe 15
        } finally {
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    test("parallel commits after AWAITING_APPROVAL transition exactly one to COMMITTED") {
        val store = factory()
        val s = scope("approved")
        store.reserve(s, "fp", Fixtures.NOW)
        store.markAwaitingApproval(s, Fixtures.NOW.plusSeconds(1))
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(16) { i ->
                Callable { store.commit(s, "job_$i", Fixtures.NOW.plusSeconds(2)) }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            results.count { it } shouldBe 1
            results.count { !it } shouldBe 15
        } finally {
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
        val final = store.reserve(s, "fp", Fixtures.NOW.plusSeconds(3))
        final.shouldBeInstanceOf<IdempotencyReserveOutcome.Committed>()
        final.resultRef.startsWith("job_") shouldBe true
    }

    test("claimApproved without an entry returns NotAwaitingApproval") {
        val store = factory()
        store.claimApproved(scope("ghost"), Fixtures.NOW)
            .shouldBeInstanceOf<IdempotencyClaimOutcome.NotAwaitingApproval>()
    }

    test("claimApproved on PENDING (pre-approval) is NotAwaitingApproval") {
        val store = factory()
        val s = scope("pre")
        store.reserve(s, "fp", Fixtures.NOW)
        store.claimApproved(s, Fixtures.NOW.plusSeconds(1))
            .shouldBeInstanceOf<IdempotencyClaimOutcome.NotAwaitingApproval>()
    }

    test("claimApproved transitions AWAITING_APPROVAL atomically — exactly one Claimed under load") {
        val store = factory()
        val s = scope("approved")
        store.reserve(s, "fp", Fixtures.NOW)
        store.markAwaitingApproval(s, Fixtures.NOW.plusSeconds(1))

        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(16) {
                Callable { store.claimApproved(s, Fixtures.NOW.plusSeconds(2)) }
            }
            val results = pool.invokeAll(tasks).map { it.get() }
            results.count { it is IdempotencyClaimOutcome.Claimed } shouldBe 1
            results.count { it is IdempotencyClaimOutcome.AlreadyClaimed } shouldBe 15
        } finally {
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    test("claimApproved on a committed entry returns Committed for dedup") {
        val store = factory()
        val s = scope("done")
        store.reserve(s, "fp", Fixtures.NOW)
        store.markAwaitingApproval(s, Fixtures.NOW.plusSeconds(1))
        store.claimApproved(s, Fixtures.NOW.plusSeconds(2))
            .shouldBeInstanceOf<IdempotencyClaimOutcome.Claimed>()
        store.commit(s, "job_1", Fixtures.NOW.plusSeconds(3))

        val outcome = store.claimApproved(s, Fixtures.NOW.plusSeconds(4))
        outcome.shouldBeInstanceOf<IdempotencyClaimOutcome.Committed>()
        outcome.resultRef shouldBe "job_1"
    }

    test("claimApproved on a denied entry returns Denied with the reason") {
        val store = factory()
        val s = scope("denied")
        store.reserve(s, "fp", Fixtures.NOW)
        store.markAwaitingApproval(s, Fixtures.NOW.plusSeconds(1))
        store.deny(s, "policy", Fixtures.NOW.plusSeconds(2))

        val outcome = store.claimApproved(s, Fixtures.NOW.plusSeconds(3))
        outcome.shouldBeInstanceOf<IdempotencyClaimOutcome.Denied>()
        outcome.reason shouldBe "policy"
    }

    test("expired AWAITING_APPROVAL is no longer claimable") {
        val store = factory()
        val s = scope("late")
        store.reserve(s, "fp", Fixtures.NOW)
        store.markAwaitingApproval(s, Fixtures.NOW.plusSeconds(1))

        store.claimApproved(s, Fixtures.NOW.plusSeconds(100_000))
            .shouldBeInstanceOf<IdempotencyClaimOutcome.NotAwaitingApproval>()
    }

    test("commit after Claimed transitions to COMMITTED and dedups subsequent reserves") {
        val store = factory()
        val s = scope("commit-after-claim")
        store.reserve(s, "fp", Fixtures.NOW)
        store.markAwaitingApproval(s, Fixtures.NOW.plusSeconds(1))
        val claim = store.claimApproved(s, Fixtures.NOW.plusSeconds(2))
        claim.shouldBeInstanceOf<IdempotencyClaimOutcome.Claimed>()

        store.commit(s, "job_42", Fixtures.NOW.plusSeconds(3)) shouldBe true

        val again = store.reserve(s, "fp", Fixtures.NOW.plusSeconds(4))
        again.shouldBeInstanceOf<IdempotencyReserveOutcome.Committed>()
        again.resultRef shouldBe "job_42"
    }

    test("cleanupExpired removes terminal entries past their retention") {
        val store = factory()
        val s1 = scope("k1")
        val s2 = scope("k2")
        store.reserve(s1, "fp1", Fixtures.NOW)
        store.commit(s1, "r", Fixtures.NOW.plusSeconds(1))
        store.reserve(s2, "fp2", Fixtures.NOW)
        store.deny(s2, "no", Fixtures.NOW.plusSeconds(1))
        val removed = store.cleanupExpired(Fixtures.NOW.plusSeconds(100_000))
        removed shouldBe 2
    }
})
