package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.ports.UploadInitClaim
import dev.dmigrate.server.ports.UploadInitClaimOutcome
import dev.dmigrate.server.ports.UploadInitClaimScope
import dev.dmigrate.server.ports.UploadInitClaimStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * LF-010 / LF-013 / LN-009 / LN-011 — In-Memory-Variante des
 * [UploadInitClaimStore]. ConcurrentHashMap.compute liefert die
 * atomare Single-Writer-Garantie fuer den LF-010 / LF-013 / LN-009 / LN-011-Vertrag.
 *
 * Production-Backings (z.B. JDBC-Postgres) muessen die gleiche
 * exactly-one-Acquired-Eigenschaft ueber DB-Atomicity bereitstellen —
 * UPSERT mit CAS-Predicate auf `lease_expires_at`/`payload_fingerprint`
 * ist die natuerliche Umsetzung.
 */
class InMemoryUploadInitClaimStore : UploadInitClaimStore {

    private val claims: ConcurrentHashMap<UploadInitClaimScope, UploadInitClaim> = ConcurrentHashMap()

    override fun acquire(
        scope: UploadInitClaimScope,
        payloadFingerprint: String,
        claimId: String,
        leaseExpiresAt: Instant,
        now: Instant,
    ): UploadInitClaimOutcome {
        var outcome: UploadInitClaimOutcome? = null
        claims.compute(scope) { _, existing ->
            outcome = computeOutcome(scope, existing, payloadFingerprint, claimId, leaseExpiresAt, now)
            when (val result = outcome!!) {
                is UploadInitClaimOutcome.Acquired -> result.claim
                is UploadInitClaimOutcome.Reclaimed -> result.claim
                is UploadInitClaimOutcome.InProgress, is UploadInitClaimOutcome.Conflict -> existing
            }
        }
        return outcome!!
    }

    override fun release(scope: UploadInitClaimScope, claimId: String): Boolean {
        var released = false
        claims.computeIfPresent(scope) { _, current ->
            if (current.claimId == claimId) {
                released = true
                null
            } else {
                current
            }
        }
        return released
    }

    override fun findById(scope: UploadInitClaimScope): UploadInitClaim? = claims[scope]

    private fun computeOutcome(
        scope: UploadInitClaimScope,
        existing: UploadInitClaim?,
        payloadFingerprint: String,
        claimId: String,
        leaseExpiresAt: Instant,
        now: Instant,
    ): UploadInitClaimOutcome {
        if (existing == null) {
            return UploadInitClaimOutcome.Acquired(
                UploadInitClaim(scope, payloadFingerprint, claimId, claimedAt = now, leaseExpiresAt = leaseExpiresAt),
            )
        }
        if (existing.payloadFingerprint != payloadFingerprint) {
            // LF-010 / LF-013 / LN-009 / LN-011: gleicher Scope, abweichender Fingerprint -> Conflict.
            // Lease-Aktivitaet spielt keine Rolle.
            return UploadInitClaimOutcome.Conflict(existing.payloadFingerprint)
        }
        // LF-010 / LF-013 / LN-009 / LN-011 + Lease-Vertrag: Active Lease blockt konkurrente
        // Acquires; abgelaufene Lease erlaubt Reclaim.
        // Negative Clock-Jumps duerfen Leases nicht verlaengern —
        // wir vergleichen `now < leaseExpiresAt`, auch wenn `now <
        // existing.claimedAt`. Active wins.
        return if (now.isBefore(existing.leaseExpiresAt)) {
            UploadInitClaimOutcome.InProgress(existing)
        } else {
            UploadInitClaimOutcome.Reclaimed(
                claim = UploadInitClaim(
                    scope = scope,
                    payloadFingerprint = payloadFingerprint,
                    claimId = claimId,
                    claimedAt = now,
                    leaseExpiresAt = leaseExpiresAt,
                ),
                previous = existing,
            )
        }
    }
}
