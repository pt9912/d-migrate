package dev.dmigrate.server.ports.contract

import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaOutcome
import dev.dmigrate.server.ports.quota.QuotaStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

abstract class QuotaStoreContractTests(factory: () -> QuotaStore) : FunSpec({

    fun key(dimension: QuotaDimension = QuotaDimension.ACTIVE_JOBS) =
        Fixtures.quotaKey(dimension = dimension)

    test("reserve increments counter when below limit") {
        val store = factory()
        val k = key()
        val outcome = store.reserve(k, amount = 1, limit = 5)
        outcome.shouldBeInstanceOf<QuotaOutcome.Granted>()
        outcome.newCurrent shouldBe 1
        store.current(k) shouldBe 1
    }

    test("reserve rejects when amount exceeds limit") {
        val store = factory()
        val k = key()
        store.reserve(k, amount = 4, limit = 5)
        val outcome = store.reserve(k, amount = 2, limit = 5)
        outcome.shouldBeInstanceOf<QuotaOutcome.RateLimited>()
        outcome.current shouldBe 4
        store.current(k) shouldBe 4
    }

    test("release decrements counter") {
        val store = factory()
        val k = key()
        store.reserve(k, amount = 3, limit = 10)
        store.release(k, amount = 2) shouldBe 1
        store.current(k) shouldBe 1
    }

    test("release floors at zero") {
        val store = factory()
        val k = key()
        store.reserve(k, amount = 1, limit = 10)
        store.release(k, amount = 5) shouldBe 0
        store.current(k) shouldBe 0
    }

    test("counters for different dimensions are independent") {
        val store = factory()
        val jobs = key(QuotaDimension.ACTIVE_JOBS)
        val uploads = key(QuotaDimension.ACTIVE_UPLOAD_SESSIONS)
        store.reserve(jobs, amount = 2, limit = 10)
        store.reserve(uploads, amount = 5, limit = 10)
        store.current(jobs) shouldBe 2
        store.current(uploads) shouldBe 5
    }

    test("parallel reserves never exceed limit") {
        val store = factory()
        val k = key()
        val limit = 10L
        val pool = Executors.newFixedThreadPool(8)
        try {
            val tasks = List(50) { Callable { store.reserve(k, amount = 1, limit = limit) } }
            val results = pool.invokeAll(tasks).map { it.get() }
            val granted = results.count { it is QuotaOutcome.Granted }
            granted shouldBe limit.toInt()
            store.current(k) shouldBe limit
        } finally {
            pool.shutdown()
            pool.awaitTermination(2, TimeUnit.SECONDS)
        }
    }
})
