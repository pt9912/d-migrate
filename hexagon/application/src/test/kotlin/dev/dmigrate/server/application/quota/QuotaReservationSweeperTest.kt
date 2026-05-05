package dev.dmigrate.server.application.quota

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference

class QuotaReservationSweeperTest : FunSpec({

    val key = QuotaKey(tenantId = TenantId("acme"), dimension = QuotaDimension.ACTIVE_JOBS)

    /**
     * Manueller Clock fuer Tests, der pro `now()` Aufruf den
     * gespeicherten Wert liefert. Erlaubt es, den Sweeper bei
     * verschiedenen Zeitpunkten ablaufen zu lassen.
     */
    class ManualClock(initial: Instant) : Clock() {
        private val current = AtomicReference(initial)
        fun setTo(t: Instant) { current.set(t) }
        override fun instant(): Instant = current.get()
        override fun withZone(zone: ZoneId?): Clock = this
        override fun getZone(): ZoneId = ZoneOffset.UTC
    }

    test("sweep refunded abgelaufene PENDING-Reservierungen exactly-once") {
        val now0 = Instant.parse("2026-05-05T12:00:00Z")
        val clock = ManualClock(now0)
        val delegateStore = InMemoryQuotaStore()
        val delegate = DefaultQuotaService(delegateStore) { 5L }
        val ownerStore = InMemoryQuotaReservationOwnerStore()
        val owned = OwnerAwareQuotaService(delegate, ownerStore)
        val sweeper = QuotaReservationSweeper(ownerStore, delegate, clock)

        // 3 Reservierungen mit verschiedenen Lease-Zeiten.
        owned.reserve(key, 1L, "o1", now0.plusSeconds(10), now0)
        owned.reserve(key, 1L, "o2", now0.plusSeconds(30), now0)
        owned.reserve(key, 1L, "o3", now0.plusSeconds(60), now0)

        // Vor Sweep: Counter = 3.
        delegate.reserve(key, 0L).shouldBeInstanceOf<QuotaOutcome.Granted>().newCurrent shouldBe 3L

        // Zeit auf 20s nach Start: o1 abgelaufen, o2/o3 noch nicht.
        clock.setTo(now0.plusSeconds(20))
        val refunded = sweeper.sweep()
        refunded shouldBe 1
        // Counter dekrementiert: 3 → 2.
        delegate.reserve(key, 0L).shouldBeInstanceOf<QuotaOutcome.Granted>().newCurrent shouldBe 2L
        ownerStore.findById("o1")!!.status shouldBe QuotaReservationStatus.REFUNDED
        ownerStore.findById("o2")!!.status shouldBe QuotaReservationStatus.PENDING

        // Zweiter Sweep am gleichen Zeitpunkt — exactly-once: o1 wird
        // NICHT erneut refunded.
        sweeper.sweep() shouldBe 0
        delegate.reserve(key, 0L).shouldBeInstanceOf<QuotaOutcome.Granted>().newCurrent shouldBe 2L
    }

    test("sweep refunded NICHT, wenn Eintrag bereits COMMITTED ist (Plan §7.9 line 1311)") {
        val now0 = Instant.parse("2026-05-05T12:00:00Z")
        val clock = ManualClock(now0)
        val delegate = DefaultQuotaService(InMemoryQuotaStore()) { 5L }
        val ownerStore = InMemoryQuotaReservationOwnerStore()
        val owned = OwnerAwareQuotaService(delegate, ownerStore)
        val sweeper = QuotaReservationSweeper(ownerStore, delegate, clock)

        owned.reserve(key, 1L, "o-committed", now0.plusSeconds(10), now0)
        // Vor Lease-Ablauf wird der Job committed.
        owned.commitForOwner("o-committed", now0.plusSeconds(5))

        // Nun Lease-Ablauf — Sweeper darf NICHT refunden, weil
        // status=COMMITTED.
        clock.setTo(now0.plusSeconds(20))
        sweeper.sweep() shouldBe 0
        ownerStore.findById("o-committed")!!.status shouldBe QuotaReservationStatus.COMMITTED
        // Counter bleibt belegt.
        delegate.reserve(key, 0L).shouldBeInstanceOf<QuotaOutcome.Granted>().newCurrent shouldBe 1L
    }

    test("sweep ist no-op wenn keine PENDING-Eintraege abgelaufen sind") {
        val now0 = Instant.parse("2026-05-05T12:00:00Z")
        val clock = ManualClock(now0)
        val delegate = DefaultQuotaService(InMemoryQuotaStore()) { 5L }
        val ownerStore = InMemoryQuotaReservationOwnerStore()
        val owned = OwnerAwareQuotaService(delegate, ownerStore)
        val sweeper = QuotaReservationSweeper(ownerStore, delegate, clock)

        owned.reserve(key, 1L, "o1", now0.plusSeconds(60), now0)
        clock.setTo(now0.plusSeconds(30)) // noch im Lease
        sweeper.sweep() shouldBe 0
        ownerStore.findById("o1")!!.status shouldBe QuotaReservationStatus.PENDING
    }

    test("sweep mehrerer abgelaufener Eintraege auf einmal") {
        val now0 = Instant.parse("2026-05-05T12:00:00Z")
        val clock = ManualClock(now0)
        val delegate = DefaultQuotaService(InMemoryQuotaStore()) { 10L }
        val ownerStore = InMemoryQuotaReservationOwnerStore()
        val owned = OwnerAwareQuotaService(delegate, ownerStore)
        val sweeper = QuotaReservationSweeper(ownerStore, delegate, clock)

        for (i in 1..5) {
            owned.reserve(key, 1L, "o$i", now0.plusSeconds(i.toLong()), now0)
        }
        // Alle bei now0+10 abgelaufen.
        clock.setTo(now0.plusSeconds(10))
        sweeper.sweep() shouldBe 5
        // Counter zurueck auf 0.
        delegate.reserve(key, 0L).shouldBeInstanceOf<QuotaOutcome.Granted>().newCurrent shouldBe 0L
    }
})
