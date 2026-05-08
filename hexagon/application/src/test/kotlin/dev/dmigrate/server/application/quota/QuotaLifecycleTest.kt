package dev.dmigrate.server.application.quota

import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class QuotaLifecycleTest : FunSpec({

    fun harness(limit: Long = 1_000_000): Pair<DefaultQuotaService, (QuotaDimension) -> Long> {
        val store = InMemoryQuotaStore()
        val current = { dim: QuotaDimension -> store.current(Fixtures.quotaKey(dim)) }
        return DefaultQuotaService(store) { limit } to current
    }

    test("commit leaves the counter at the reserved value (audit-only no-op)") {
        val (service, current) = harness()
        val outcome = service.reserve(Fixtures.quotaKey(), 2)
            .shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.commit(QuotaReservation.of(outcome))
        current(QuotaDimension.ACTIVE_JOBS) shouldBe 2
    }

    test("release on terminal status decrements the counter") {
        val (service, current) = harness()
        val granted = service.reserve(Fixtures.quotaKey(), 3)
            .shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.release(QuotaReservation.of(granted))
        current(QuotaDimension.ACTIVE_JOBS) shouldBe 0
    }

    test("refund on idempotency-replay decrements the counter the same way") {
        val (service, current) = harness()
        val granted = service.reserve(Fixtures.quotaKey(), 2)
            .shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.refund(QuotaReservation.of(granted))
        current(QuotaDimension.ACTIVE_JOBS) shouldBe 0
    }

    test("multi-reserve compensation: failed second reserve leaves first released") {
        val (service, current) = harness(limit = 2)
        val jobs = Fixtures.quotaKey(QuotaDimension.ACTIVE_JOBS)
        val bytes = Fixtures.quotaKey(QuotaDimension.UPLOAD_BYTES)

        service.reserve(bytes, 2).shouldBeInstanceOf<QuotaOutcome.Granted>()

        val firstGranted = service.reserve(jobs, 1).shouldBeInstanceOf<QuotaOutcome.Granted>()
        val secondOutcome = service.reserve(bytes, 1)
        secondOutcome.shouldBeInstanceOf<QuotaOutcome.RateLimited>()

        service.refund(QuotaReservation.of(firstGranted))
        current(QuotaDimension.ACTIVE_JOBS) shouldBe 0
        current(QuotaDimension.UPLOAD_BYTES) shouldBe 2
    }

    test("upload-finalize success: counter drops via release") {
        val (service, current) = harness()
        val granted = service.reserve(Fixtures.quotaKey(QuotaDimension.UPLOAD_BYTES), 1024)
            .shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.release(QuotaReservation.of(granted))
        current(QuotaDimension.UPLOAD_BYTES) shouldBe 0
    }

    test("upload-abort path: counter drops via refund (different audit reason, same effect)") {
        val (service, current) = harness()
        val granted = service.reserve(Fixtures.quotaKey(QuotaDimension.UPLOAD_BYTES), 1024)
            .shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.refund(QuotaReservation.of(granted))
        current(QuotaDimension.UPLOAD_BYTES) shouldBe 0
    }

    test("release of a never-reserved key is a no-op (counter stays at 0)") {
        val (service, current) = harness()
        service.release(QuotaReservation(Fixtures.quotaKey(), 5))
        current(QuotaDimension.ACTIVE_JOBS) shouldBe 0
    }
})
