package dev.dmigrate.server.ports.contract

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
