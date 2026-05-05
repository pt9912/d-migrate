package dev.dmigrate.server.application.quota

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class QuotaReservationOwnerStoreTest : FunSpec({

    val now = Instant.parse("2026-05-05T12:00:00Z")
    val key = QuotaKey(tenantId = TenantId("acme"), dimension = QuotaDimension.ACTIVE_JOBS)
    val reservation = QuotaReservation(key, 1L)

    test("register erzeugt PENDING-Eintrag") {
        val store = InMemoryQuotaReservationOwnerStore()
        val owner = store.register("o1", reservation, now.plusSeconds(60), now)
        owner.status shouldBe QuotaReservationStatus.PENDING
        owner.ownerId shouldBe "o1"
        owner.leaseExpiresAt shouldBe now.plusSeconds(60)
        owner.createdAt shouldBe now
        store.findById("o1") shouldBe owner
    }

    test("Doppel-register fuer denselben ownerId wirft") {
        val store = InMemoryQuotaReservationOwnerStore()
        store.register("o1", reservation, now.plusSeconds(60), now)
        shouldThrow<IllegalArgumentException> {
            store.register("o1", reservation, now.plusSeconds(120), now.plusSeconds(1))
        }
    }

    test("markCommitted: PENDING → COMMITTED, updatedAt aktualisiert") {
        val store = InMemoryQuotaReservationOwnerStore()
        store.register("o1", reservation, now.plusSeconds(60), now)
        val updated = store.markCommitted("o1", now.plusSeconds(5))
        updated!!.status shouldBe QuotaReservationStatus.COMMITTED
        updated.updatedAt shouldBe now.plusSeconds(5)
        store.findById("o1")!!.status shouldBe QuotaReservationStatus.COMMITTED
    }

    test("markCommitted: schon COMMITTED → null (no-op)") {
        val store = InMemoryQuotaReservationOwnerStore()
        store.register("o1", reservation, now.plusSeconds(60), now)
        store.markCommitted("o1", now.plusSeconds(5))
        store.markCommitted("o1", now.plusSeconds(10)) shouldBe null
    }

    test("markCommitted: schon REFUNDED → null (terminale Stati absorbierend)") {
        val store = InMemoryQuotaReservationOwnerStore()
        store.register("o1", reservation, now.plusSeconds(60), now)
        store.markRefunded("o1", now.plusSeconds(5))
        store.markCommitted("o1", now.plusSeconds(10)) shouldBe null
    }

    test("markRefunded: PENDING → REFUNDED") {
        val store = InMemoryQuotaReservationOwnerStore()
        store.register("o1", reservation, now.plusSeconds(60), now)
        val refunded = store.markRefunded("o1", now.plusSeconds(5))
        refunded!!.status shouldBe QuotaReservationStatus.REFUNDED
    }

    test("markRefunded: schon COMMITTED → null (kein Refund nach erfolgreichem Job-Commit)") {
        val store = InMemoryQuotaReservationOwnerStore()
        store.register("o1", reservation, now.plusSeconds(60), now)
        store.markCommitted("o1", now.plusSeconds(5))
        store.markRefunded("o1", now.plusSeconds(10)) shouldBe null
    }

    test("markReleased: COMMITTED → RELEASED") {
        val store = InMemoryQuotaReservationOwnerStore()
        store.register("o1", reservation, now.plusSeconds(60), now)
        store.markCommitted("o1", now.plusSeconds(5))
        val released = store.markReleased("o1", now.plusSeconds(10))
        released!!.status shouldBe QuotaReservationStatus.RELEASED
    }

    test("markReleased: PENDING (ohne vorheriges Commit) → null") {
        val store = InMemoryQuotaReservationOwnerStore()
        store.register("o1", reservation, now.plusSeconds(60), now)
        store.markReleased("o1", now.plusSeconds(5)) shouldBe null
    }

    test("listExpiredPending: nur PENDING + leaseExpiresAt <= now") {
        val store = InMemoryQuotaReservationOwnerStore()
        store.register("o-fresh", reservation, now.plusSeconds(60), now)
        store.register("o-stale", reservation, now.plusSeconds(1), now)
        store.register("o-committed", reservation, now.plusSeconds(1), now)
        store.markCommitted("o-committed", now)

        val expired = store.listExpiredPending(now.plusSeconds(2))
        expired.map { it.ownerId } shouldBe listOf("o-stale")
    }

    test("listExpiredPending: leaseExpiresAt == now → enthalten (boundary)") {
        val store = InMemoryQuotaReservationOwnerStore()
        store.register("o1", reservation, now, now)
        store.listExpiredPending(now).map { it.ownerId } shouldBe listOf("o1")
    }
})
