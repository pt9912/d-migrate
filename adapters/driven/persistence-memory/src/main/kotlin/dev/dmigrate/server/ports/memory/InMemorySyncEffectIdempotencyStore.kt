package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome
import dev.dmigrate.server.core.idempotency.SyncEffectScope
import dev.dmigrate.server.ports.SyncEffectIdempotencyStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemorySyncEffectIdempotencyStore(
    private val pendingLeaseSeconds: Long = 60,
    private val committedRetentionSeconds: Long = 86_400,
) : SyncEffectIdempotencyStore {

    private data class Entry(
        val scope: SyncEffectScope,
        val fingerprint: String,
        val expiresAt: Instant,
        val resultRef: String? = null,
    )

    private val entries = ConcurrentHashMap<SyncEffectScope, Entry>()

    override fun reserve(
        scope: SyncEffectScope,
        payloadFingerprint: String,
        now: Instant,
    ): SyncEffectReserveOutcome {
        var outcome: SyncEffectReserveOutcome? = null
        entries.compute(scope) { _, existing ->
            when {
                existing == null -> {
                    val expires = now.plusSeconds(pendingLeaseSeconds)
                    outcome = SyncEffectReserveOutcome.Reserved(scope, expires)
                    Entry(scope, payloadFingerprint, expires)
                }
                existing.fingerprint != payloadFingerprint -> {
                    outcome = SyncEffectReserveOutcome.Conflict(scope, existing.fingerprint)
                    existing
                }
                existing.resultRef != null -> {
                    outcome = SyncEffectReserveOutcome.Existing(scope, existing.resultRef)
                    existing
                }
                else -> {
                    outcome = SyncEffectReserveOutcome.Reserved(scope, existing.expiresAt)
                    existing
                }
            }
        }
        return outcome!!
    }

    override fun commit(scope: SyncEffectScope, resultRef: String, now: Instant): Boolean {
        var transitioned = false
        entries.computeIfPresent(scope) { _, existing ->
            if (existing.resultRef == null) {
                transitioned = true
                existing.copy(
                    resultRef = resultRef,
                    expiresAt = now.plusSeconds(committedRetentionSeconds),
                )
            } else {
                existing
            }
        }
        return transitioned
    }

    override fun cleanupExpired(now: Instant): Int {
        val expiredKeys = entries.entries
            .filter { it.value.resultRef != null && it.value.expiresAt.isBefore(now) }
            .map { it.key }
        expiredKeys.forEach { entries.remove(it) }
        return expiredKeys.size
    }
}
