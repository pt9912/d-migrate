package dev.dmigrate.server.application.quota

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class OwnerAwareQuotaServiceTest : FunSpec({

    val now = Instant.parse("2026-05-05T12:00:00Z")
    val key = QuotaKey(tenantId = TenantId("acme"), dimension = QuotaDimension.ACTIVE_JOBS)

    fun fixture(limit: Long = 2L): Triple<DefaultQuotaService, InMemoryQuotaReservationOwnerStore, OwnerAwareQuotaService> {
        val delegate = DefaultQuotaService(InMemoryQuotaStore()) { limit }
        val ownerStore = InMemoryQuotaReservationOwnerStore()
        val owned = OwnerAwareQuotaService(delegate, ownerStore)
        return Triple(delegate, ownerStore, owned)
    }

    test("Granted: Reserve registriert Owner-Eintrag mit PENDING + Lease") {
        val (_, ownerStore, owned) = fixture()
        val outcome = owned.reserve(key, 1L, "owner-1", now.plusSeconds(60), now)
        outcome.shouldBeInstanceOf<QuotaOutcome.Granted>()
        val owner = ownerStore.findById("owner-1")!!
        owner.status shouldBe QuotaReservationStatus.PENDING
        owner.leaseExpiresAt shouldBe now.plusSeconds(60)
        owner.reservation.amount shouldBe 1L
    }

    test("RateLimited: KEIN Owner-Eintrag (kein side-effect am Owner-Store)") {
        val (_, ownerStore, owned) = fixture(limit = 0L) // jeder Reserve ueberschreitet
        val outcome = owned.reserve(key, 1L, "owner-x", now.plusSeconds(60), now)
        outcome.shouldBeInstanceOf<QuotaOutcome.RateLimited>()
        ownerStore.findById("owner-x").shouldBeNull()
    }

    test("commitForOwner: PENDING → COMMITTED, Counter bleibt belegt") {
        val (delegate, ownerStore, owned) = fixture()
        owned.reserve(key, 1L, "o1", now.plusSeconds(60), now)
        owned.commitForOwner("o1", now.plusSeconds(5))
        ownerStore.findById("o1")!!.status shouldBe QuotaReservationStatus.COMMITTED
        // Counter weiterhin auf 1 (commit dekrementiert nicht).
        val outcome = delegate.reserve(key, 1L) // limit=2, current=1 → noch frei
        outcome.shouldBeInstanceOf<QuotaOutcome.Granted>()
    }

    test("releaseForOwner: COMMITTED → RELEASED, Counter dekrementiert") {
        val (delegate, ownerStore, owned) = fixture(limit = 1L) // tight limit
        owned.reserve(key, 1L, "o1", now.plusSeconds(60), now)
        owned.commitForOwner("o1", now.plusSeconds(5))
        // Vor Release ist Counter belegt — neuer Reserve ueberschreitet.
        delegate.reserve(key, 1L).shouldBeInstanceOf<QuotaOutcome.RateLimited>()

        owned.releaseForOwner("o1", now.plusSeconds(10))
        ownerStore.findById("o1")!!.status shouldBe QuotaReservationStatus.RELEASED
        // Slot frei.
        delegate.reserve(key, 1L).shouldBeInstanceOf<QuotaOutcome.Granted>()
    }

    test("refundForOwner: PENDING → REFUNDED, Counter dekrementiert") {
        val (delegate, ownerStore, owned) = fixture(limit = 1L)
        owned.reserve(key, 1L, "o1", now.plusSeconds(60), now)
        owned.refundForOwner("o1", now.plusSeconds(5))
        ownerStore.findById("o1")!!.status shouldBe QuotaReservationStatus.REFUNDED
        // Slot frei.
        delegate.reserve(key, 1L).shouldBeInstanceOf<QuotaOutcome.Granted>()
    }

    test("refundForOwner nach commit ist no-op (Plan §7.9 line 1278: commit haelt den Slot)") {
        val (delegate, ownerStore, owned) = fixture(limit = 1L)
        owned.reserve(key, 1L, "o1", now.plusSeconds(60), now)
        owned.commitForOwner("o1", now.plusSeconds(5))
        owned.refundForOwner("o1", now.plusSeconds(10))
        // Status bleibt COMMITTED — kein Refund nach Commit.
        ownerStore.findById("o1")!!.status shouldBe QuotaReservationStatus.COMMITTED
        delegate.reserve(key, 1L).shouldBeInstanceOf<QuotaOutcome.RateLimited>()
    }

    test("Lifecycle ohne Owner-Eintrag (z.B. nicht-owner-aware Reserve) ist no-op safe") {
        val (_, _, owned) = fixture()
        // Kein vorheriges reserve → keine Owner-Bindung.
        owned.commitForOwner("ghost", now)
        owned.releaseForOwner("ghost", now)
        owned.refundForOwner("ghost", now)
        // Keine Exception, keine state-leaks.
    }
})
