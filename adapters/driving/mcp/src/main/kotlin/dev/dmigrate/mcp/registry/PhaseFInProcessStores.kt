package dev.dmigrate.mcp.registry

import dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome
import dev.dmigrate.server.core.idempotency.SyncEffectScope
import dev.dmigrate.server.core.upload.AbortOutcome
import dev.dmigrate.server.ports.AbortOutcomeStore
import dev.dmigrate.server.ports.SyncEffectIdempotencyStore
import dev.dmigrate.server.ports.UploadInitClaim
import dev.dmigrate.server.ports.UploadInitClaimOutcome
import dev.dmigrate.server.ports.UploadInitClaimScope
import dev.dmigrate.server.ports.UploadInitClaimStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

internal class InProcessSyncEffectIdempotencyStore(
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
                existing == null || (existing.resultRef == null && existing.expiresAt.isBefore(now)) -> {
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
                existing.copy(resultRef = resultRef, expiresAt = now.plusSeconds(committedRetentionSeconds))
            } else {
                existing
            }
        }
        return transitioned
    }

    override fun cleanupExpired(now: Instant): Int {
        val expiredKeys = entries.entries
            .filter { it.value.expiresAt.isBefore(now) }
            .map { it.key }
        expiredKeys.forEach(entries::remove)
        return expiredKeys.size
    }
}

internal class InProcessUploadInitClaimStore : UploadInitClaimStore {

    private val claims = ConcurrentHashMap<UploadInitClaimScope, UploadInitClaim>()

    override fun acquire(
        scope: UploadInitClaimScope,
        payloadFingerprint: String,
        claimId: String,
        leaseExpiresAt: Instant,
        now: Instant,
    ): UploadInitClaimOutcome {
        var outcome: UploadInitClaimOutcome? = null
        claims.compute(scope) { _, existing ->
            val result = computeAcquire(scope, payloadFingerprint, claimId, leaseExpiresAt, now, existing)
            outcome = result
            when (result) {
                is UploadInitClaimOutcome.Acquired -> result.claim
                is UploadInitClaimOutcome.Reclaimed -> result.claim
                is UploadInitClaimOutcome.InProgress,
                is UploadInitClaimOutcome.Conflict -> existing
            }
        }
        return outcome!!
    }

    override fun release(scope: UploadInitClaimScope, claimId: String): Boolean {
        var released = false
        claims.computeIfPresent(scope) { _, existing ->
            if (existing.claimId == claimId) {
                released = true
                null
            } else {
                existing
            }
        }
        return released
    }

    override fun findById(scope: UploadInitClaimScope): UploadInitClaim? = claims[scope]

    private fun computeAcquire(
        scope: UploadInitClaimScope,
        payloadFingerprint: String,
        claimId: String,
        leaseExpiresAt: Instant,
        now: Instant,
        existing: UploadInitClaim?,
    ): UploadInitClaimOutcome {
        if (existing == null) {
            return UploadInitClaimOutcome.Acquired(
                UploadInitClaim(scope, payloadFingerprint, claimId, claimedAt = now, leaseExpiresAt = leaseExpiresAt),
            )
        }
        if (existing.payloadFingerprint != payloadFingerprint) {
            return UploadInitClaimOutcome.Conflict(existing.payloadFingerprint)
        }
        return if (existing.leaseExpiresAt.isBefore(now)) {
            UploadInitClaimOutcome.Reclaimed(
                claim = UploadInitClaim(scope, payloadFingerprint, claimId, claimedAt = now, leaseExpiresAt = leaseExpiresAt),
                previous = existing,
            )
        } else {
            UploadInitClaimOutcome.InProgress(existing)
        }
    }
}

internal class InProcessAbortOutcomeStore : AbortOutcomeStore {

    private val entries = ConcurrentHashMap<String, AbortOutcome>()

    override fun save(resultRef: String, outcome: AbortOutcome): AbortOutcomeStore.SaveOutcome {
        var result: AbortOutcomeStore.SaveOutcome? = null
        entries.compute(resultRef) { _, existing ->
            when {
                existing == null -> {
                    result = AbortOutcomeStore.SaveOutcome.Stored(resultRef, outcome)
                    outcome
                }
                existing.abortFingerprint == outcome.abortFingerprint -> {
                    result = AbortOutcomeStore.SaveOutcome.AlreadyStored(existing)
                    existing
                }
                else -> {
                    result = AbortOutcomeStore.SaveOutcome.Conflict(
                        existingFingerprint = existing.abortFingerprint,
                        attemptedFingerprint = outcome.abortFingerprint,
                    )
                    existing
                }
            }
        }
        return result!!
    }

    override fun findByResultRef(resultRef: String): AbortOutcome? = entries[resultRef]
}
