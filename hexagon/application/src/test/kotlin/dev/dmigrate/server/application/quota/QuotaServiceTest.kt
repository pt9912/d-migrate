package dev.dmigrate.server.application.quota

import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.memory.InMemoryQuotaStore
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class QuotaServiceTest : FunSpec({

    fun newService(limit: Long = 4L): Pair<DefaultQuotaService, InMemoryQuotaStore> {
        val store = InMemoryQuotaStore()
        return DefaultQuotaService(store) { limit } to store
    }

    test("active jobs reserve until limit, then RateLimited") {
        val (service, _) = newService(limit = 2)
        val k = Fixtures.quotaKey(QuotaDimension.ACTIVE_JOBS)
        service.reserve(k, 1).shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.reserve(k, 1).shouldBeInstanceOf<QuotaOutcome.Granted>()
        val third = service.reserve(k, 1)
        third.shouldBeInstanceOf<QuotaOutcome.RateLimited>()
        third.current shouldBe 2
        third.limit shouldBe 2
    }

    test("active upload sessions are counted independently from active jobs") {
        val (service, _) = newService(limit = 1)
        val jobs = Fixtures.quotaKey(QuotaDimension.ACTIVE_JOBS)
        val sessions = Fixtures.quotaKey(QuotaDimension.ACTIVE_UPLOAD_SESSIONS)
        service.reserve(jobs, 1).shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.reserve(sessions, 1).shouldBeInstanceOf<QuotaOutcome.Granted>()
    }

    test("upload bytes accept large reservations up to limit") {
        val (service, _) = newService(limit = 1_000_000)
        val k = Fixtures.quotaKey(QuotaDimension.UPLOAD_BYTES)
        service.reserve(k, 600_000).shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.reserve(k, 500_000).shouldBeInstanceOf<QuotaOutcome.RateLimited>()
        service.reserve(k, 400_000).shouldBeInstanceOf<QuotaOutcome.Granted>()
    }

    test("parallel segment writes share a single bucket per session-key") {
        val (service, _) = newService(limit = 3)
        val k = Fixtures.quotaKey(QuotaDimension.PARALLEL_SEGMENT_WRITES)
        repeat(3) { service.reserve(k, 1).shouldBeInstanceOf<QuotaOutcome.Granted>() }
        service.reserve(k, 1).shouldBeInstanceOf<QuotaOutcome.RateLimited>()
    }

    test("provider calls quota is keyed by dimension PROVIDER_CALLS") {
        val (service, _) = newService(limit = 2)
        val k = Fixtures.quotaKey(QuotaDimension.PROVIDER_CALLS)
        service.reserve(k, 1).shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.reserve(k, 1).shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.reserve(k, 1).shouldBeInstanceOf<QuotaOutcome.RateLimited>()
    }

    test("limitFor receives the QuotaKey and can vary by dimension") {
        val store = InMemoryQuotaStore()
        val limits = mapOf(
            QuotaDimension.ACTIVE_JOBS to 5L,
            QuotaDimension.UPLOAD_BYTES to 1_000L,
        )
        val service = DefaultQuotaService(store) { key -> limits.getValue(key.dimension) }
        service.reserve(Fixtures.quotaKey(QuotaDimension.ACTIVE_JOBS), 5)
            .shouldBeInstanceOf<QuotaOutcome.Granted>()
        service.reserve(Fixtures.quotaKey(QuotaDimension.UPLOAD_BYTES), 1_000)
            .shouldBeInstanceOf<QuotaOutcome.Granted>()
    }
})
