package dev.dmigrate.server.application.quota

import dev.dmigrate.server.ports.contract.Fixtures
import dev.dmigrate.server.ports.contract.MutableClock
import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaOutcome
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class FixedWindowRateLimiterTest : FunSpec({

    val key = Fixtures.quotaKey(QuotaDimension.PROVIDER_CALLS)

    test("acquires up to the limit then RateLimits within the same minute") {
        val limiter = FixedWindowRateLimiter(MutableClock(Instant.parse("2026-04-25T10:00:00Z")))
        repeat(3) { limiter.tryAcquire(key, 1, limit = 3).shouldBeInstanceOf<QuotaOutcome.Granted>() }
        val limited = limiter.tryAcquire(key, 1, limit = 3)
        limited.shouldBeInstanceOf<QuotaOutcome.RateLimited>()
        limited.current shouldBe 3
        limited.limit shouldBe 3
    }

    test("rolls over deterministically when the minute advances") {
        val clock = MutableClock(Instant.parse("2026-04-25T10:00:30Z"))
        val limiter = FixedWindowRateLimiter(clock)
        repeat(2) { limiter.tryAcquire(key, 1, limit = 2).shouldBeInstanceOf<QuotaOutcome.Granted>() }
        limiter.tryAcquire(key, 1, limit = 2).shouldBeInstanceOf<QuotaOutcome.RateLimited>()

        clock.now = Instant.parse("2026-04-25T10:01:05Z")
        repeat(2) { limiter.tryAcquire(key, 1, limit = 2).shouldBeInstanceOf<QuotaOutcome.Granted>() }
    }

    test("different keys hold independent buckets") {
        val limiter = FixedWindowRateLimiter(MutableClock(Instant.parse("2026-04-25T10:00:00Z")))
        val a = Fixtures.quotaKey(QuotaDimension.PROVIDER_CALLS, tenant = "acme")
        val b = Fixtures.quotaKey(QuotaDimension.PROVIDER_CALLS, tenant = "initech")
        repeat(2) { limiter.tryAcquire(a, 1, limit = 2).shouldBeInstanceOf<QuotaOutcome.Granted>() }
        limiter.tryAcquire(a, 1, limit = 2).shouldBeInstanceOf<QuotaOutcome.RateLimited>()
        repeat(2) { limiter.tryAcquire(b, 1, limit = 2).shouldBeInstanceOf<QuotaOutcome.Granted>() }
    }

    test("amount > 1 consumes that many slots in the bucket") {
        val limiter = FixedWindowRateLimiter(MutableClock(Instant.parse("2026-04-25T10:00:00Z")))
        limiter.tryAcquire(key, 5, limit = 10).shouldBeInstanceOf<QuotaOutcome.Granted>()
        limiter.tryAcquire(key, 6, limit = 10).shouldBeInstanceOf<QuotaOutcome.RateLimited>()
        limiter.tryAcquire(key, 5, limit = 10).shouldBeInstanceOf<QuotaOutcome.Granted>()
    }
})
