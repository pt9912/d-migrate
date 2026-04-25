package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.ports.quota.QuotaKey
import dev.dmigrate.server.ports.quota.QuotaOutcome
import dev.dmigrate.server.ports.quota.QuotaStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class InMemoryQuotaStore : QuotaStore {

    private val counters = ConcurrentHashMap<QuotaKey, AtomicLong>()

    override fun reserve(key: QuotaKey, amount: Long, limit: Long): QuotaOutcome {
        val counter = counters.computeIfAbsent(key) { AtomicLong(0) }
        while (true) {
            val current = counter.get()
            val next = current + amount
            if (next > limit) {
                return QuotaOutcome.RateLimited(
                    key = key,
                    amount = amount,
                    current = current,
                    limit = limit,
                )
            }
            if (counter.compareAndSet(current, next)) {
                return QuotaOutcome.Granted(
                    key = key,
                    amount = amount,
                    newCurrent = next,
                    limit = limit,
                )
            }
        }
    }

    override fun release(key: QuotaKey, amount: Long): Long {
        val counter = counters[key] ?: return 0
        while (true) {
            val current = counter.get()
            val next = (current - amount).coerceAtLeast(0)
            if (counter.compareAndSet(current, next)) return next
        }
    }

    override fun current(key: QuotaKey): Long = counters[key]?.get() ?: 0
}
