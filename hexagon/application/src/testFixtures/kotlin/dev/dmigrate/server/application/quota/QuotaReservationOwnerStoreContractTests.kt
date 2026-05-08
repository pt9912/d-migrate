package dev.dmigrate.server.application.quota

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase E §7.9 / `spec/phase-e-port-atomicity.md` (5) Atomicity-
 * Vertraege fuer [QuotaReservationOwnerStore]-Implementoren.
 *
 * Kritische Eigenschaften:
 *
 * - `register` ist `putIfAbsent`-CAS — Doppel-register fuer denselben
 *   ownerId wirft.
 * - `markCommitted` / `markReleased` / `markRefunded` sind exactly-once-
 *   CAS gegen den jeweils erlaubten Vorgaengerstatus. Verlierer
 *   bekommen `null`.
 * - Terminale Stati `RELEASED` und `REFUNDED` sind absorbierend.
 * - `listExpiredPending` zeigt NUR `PENDING`-Eintraege mit
 *   `leaseExpiresAt <= now` (Boundary `==` enthalten).
 *
 * Implementoren leiten ab und uebergeben ihre Factory:
 *
 * ```
 * class MyDbBackedQuotaReservationOwnerStoreContractTest :
 *     QuotaReservationOwnerStoreContractTests({ MyDbBackedStore(...) })
 * ```
 */
abstract class QuotaReservationOwnerStoreContractTests(
    factory: () -> QuotaReservationOwnerStore,
) : FunSpec({

    val now = Instant.parse("2026-05-05T12:00:00Z")
    val key = QuotaKey(tenantId = TenantId("acme"), dimension = QuotaDimension.ACTIVE_JOBS)
    val reservation = QuotaReservation(key, 1L)

    test("register erzeugt PENDING-Eintrag mit createdAt + leaseExpiresAt") {
        val store = factory()
        val owner = store.register("o1", reservation, now.plusSeconds(60), now)
        owner.status shouldBe QuotaReservationStatus.PENDING
        owner.ownerId shouldBe "o1"
        owner.leaseExpiresAt shouldBe now.plusSeconds(60)
        owner.createdAt shouldBe now
        store.findById("o1") shouldBe owner
    }

    test("Doppel-register fuer denselben ownerId wirft (putIfAbsent-CAS)") {
        val store = factory()
        store.register("o1", reservation, now.plusSeconds(60), now)
        shouldThrow<IllegalArgumentException> {
            store.register("o1", reservation, now.plusSeconds(120), now.plusSeconds(1))
        }
    }

    test("markCommitted: PENDING → COMMITTED, updatedAt aktualisiert") {
        val store = factory()
        store.register("o1", reservation, now.plusSeconds(60), now)
        val updated = store.markCommitted("o1", now.plusSeconds(5))
        updated!!.status shouldBe QuotaReservationStatus.COMMITTED
        updated.updatedAt shouldBe now.plusSeconds(5)
        store.findById("o1")!!.status shouldBe QuotaReservationStatus.COMMITTED
    }

    test("markCommitted: bereits COMMITTED → null (no-op)") {
        val store = factory()
        store.register("o1", reservation, now.plusSeconds(60), now)
        store.markCommitted("o1", now.plusSeconds(5))
        store.markCommitted("o1", now.plusSeconds(10)) shouldBe null
    }

    test("markCommitted: bereits REFUNDED → null (terminale Stati absorbierend)") {
        val store = factory()
        store.register("o1", reservation, now.plusSeconds(60), now)
        store.markRefunded("o1", now.plusSeconds(5))
        store.markCommitted("o1", now.plusSeconds(10)) shouldBe null
    }

    test("markRefunded: PENDING → REFUNDED") {
        val store = factory()
        store.register("o1", reservation, now.plusSeconds(60), now)
        val refunded = store.markRefunded("o1", now.plusSeconds(5))
        refunded!!.status shouldBe QuotaReservationStatus.REFUNDED
    }

    test("markRefunded: bereits COMMITTED → null (kein Refund nach Job-Commit)") {
        val store = factory()
        store.register("o1", reservation, now.plusSeconds(60), now)
        store.markCommitted("o1", now.plusSeconds(5))
        store.markRefunded("o1", now.plusSeconds(10)) shouldBe null
    }

    test("markReleased: COMMITTED → RELEASED") {
        val store = factory()
        store.register("o1", reservation, now.plusSeconds(60), now)
        store.markCommitted("o1", now.plusSeconds(5))
        val released = store.markReleased("o1", now.plusSeconds(10))
        released!!.status shouldBe QuotaReservationStatus.RELEASED
    }

    test("markReleased: PENDING (ohne vorheriges Commit) → null") {
        val store = factory()
        store.register("o1", reservation, now.plusSeconds(60), now)
        store.markReleased("o1", now.plusSeconds(5)) shouldBe null
    }

    test("listExpiredPending: nur PENDING + leaseExpiresAt <= now") {
        val store = factory()
        store.register("o-fresh", reservation, now.plusSeconds(60), now)
        store.register("o-stale", reservation, now.plusSeconds(1), now)
        store.register("o-committed", reservation, now.plusSeconds(1), now)
        store.markCommitted("o-committed", now)

        val expired = store.listExpiredPending(now.plusSeconds(2))
        expired.map { it.ownerId } shouldBe listOf("o-stale")
    }

    test("listExpiredPending: leaseExpiresAt == now → enthalten (boundary)") {
        val store = factory()
        store.register("o1", reservation, now, now)
        store.listExpiredPending(now).map { it.ownerId } shouldBe listOf("o1")
    }

    test("Atomicity (Re-Review #5): parallele markReleased-Calls auf gleichem Owner gewinnen genau einmal") {
        val store = factory()
        store.register("o-contended", reservation, now.plusSeconds(60), now)
        store.markCommitted("o-contended", now.plusSeconds(1))

        val pool = Executors.newFixedThreadPool(8)
        val winners = AtomicInteger(0)
        try {
            val tasks = (1..16).map {
                Callable {
                    val result = store.markReleased("o-contended", now.plusSeconds(2))
                    if (result != null) winners.incrementAndGet()
                }
            }
            pool.invokeAll(tasks).forEach { it.get() }
            // Plan §7.9 / Review-Fix #5: exactly-once.
            winners.get() shouldBe 1
            store.findById("o-contended")!!.status shouldBe QuotaReservationStatus.RELEASED
        } finally {
            pool.shutdown()
        }
    }

    test("Atomicity: parallele markRefunded-Calls auf gleichem Owner gewinnen genau einmal") {
        val store = factory()
        store.register("o-refund", reservation, now.plusSeconds(60), now)

        val pool = Executors.newFixedThreadPool(8)
        val winners = AtomicInteger(0)
        try {
            val tasks = (1..16).map {
                Callable {
                    val result = store.markRefunded("o-refund", now.plusSeconds(1))
                    if (result != null) winners.incrementAndGet()
                }
            }
            pool.invokeAll(tasks).forEach { it.get() }
            winners.get() shouldBe 1
        } finally {
            pool.shutdown()
        }
    }

    test("Atomicity: parallele register-Calls fuer SAME ownerId — exactly einer gewinnt, alle anderen werfen") {
        val store = factory()
        val pool = Executors.newFixedThreadPool(8)
        val successes = AtomicInteger(0)
        val failures = AtomicInteger(0)
        try {
            val tasks = (1..16).map {
                Callable {
                    try {
                        store.register("o-race", reservation, now.plusSeconds(60), now)
                        successes.incrementAndGet()
                    } catch (_: IllegalArgumentException) {
                        failures.incrementAndGet()
                    }
                }
            }
            pool.invokeAll(tasks).forEach { it.get() }
            successes.get() shouldBe 1
            failures.get() shouldBe 15
        } finally {
            pool.shutdown()
        }
    }
})
