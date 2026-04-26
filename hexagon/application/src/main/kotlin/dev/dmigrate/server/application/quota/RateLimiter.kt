package dev.dmigrate.server.application.quota

import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface RateLimiter {

    fun tryAcquire(key: QuotaKey, amount: Long, limit: Long): QuotaOutcome
}

/**
 * Phase A rate limiter: fixed minute window keyed by `(QuotaKey, minute)`.
 * Buckets reset deterministically on the minute boundary. Sliding-window
 * or token-bucket implementations can replace this without changing the
 * interface.
 */
class FixedWindowRateLimiter(private val clock: Clock) : RateLimiter {

    private data class WindowKey(val key: QuotaKey, val minuteEpoch: Long)

    // Phase A: stale buckets (older than the current minute) accumulate
    // until process restart. With N tenants × M dimensions × 1 entry per
    // minute the in-memory footprint is bounded by uptime, not by load.
    // A scheduled `purgeStale()` hook lands together with the AP 6.6
    // production-grade rate-limiter in 0.9.7+.
    private val buckets = ConcurrentHashMap<WindowKey, AtomicLong>()

    override fun tryAcquire(key: QuotaKey, amount: Long, limit: Long): QuotaOutcome {
        val window = WindowKey(key, currentMinute())
        val bucket = buckets.computeIfAbsent(window) { AtomicLong(0) }
        while (true) {
            val current = bucket.get()
            val next = current + amount
            if (next > limit) {
                return QuotaOutcome.RateLimited(
                    key = key,
                    amount = amount,
                    current = current,
                    limit = limit,
                )
            }
            if (bucket.compareAndSet(current, next)) {
                return QuotaOutcome.Granted(
                    key = key,
                    amount = amount,
                    newCurrent = next,
                    limit = limit,
                )
            }
        }
    }

    private fun currentMinute(): Long =
        Instant.now(clock).truncatedTo(ChronoUnit.MINUTES).epochSecond / SECONDS_PER_MINUTE

    private companion object {
        private const val SECONDS_PER_MINUTE = 60L
    }
}
