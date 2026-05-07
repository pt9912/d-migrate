package dev.dmigrate.mcp.registry

import dev.dmigrate.server.core.ai.AiToolAcquireOutcome
import dev.dmigrate.server.core.ai.AiToolClaimId
import dev.dmigrate.server.core.ai.AiToolOutcome
import dev.dmigrate.server.core.ai.AiToolScope
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.ports.AiToolOutcomeStore
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase G § 6 G.6 (G.6.a 2/2) — in-process [AiToolOutcomeStore]
 * mit Single-Writer-Lease + Reclaim.
 *
 * Plan-§-6-G.6-Carve-out, den dieser Store explizit löst:
 *
 * > "Der bestehende SyncEffectIdempotencyStore reicht unverändert
 * > nicht aus, wenn parallele gleiche Pending-Reserves erneut
 * > Reserved liefern."
 *
 * Phase F's [InProcessSyncEffectIdempotencyStore.reserve] fällt bei
 * einer aktiven Lease und identischem Fingerprint auf
 * `Reserved(existing.expiresAt)` zurück (`PhaseFInProcessStores.kt:51`).
 * Das ist für sequenzielle Upload-Init-Pfade okay; für KI-Pfade
 * bedeutet es einen zweiten Provider-Aufruf + ein zweites Artefakt.
 *
 * Hier blockiert der zweite Caller stattdessen mit
 * [AiToolAcquireOutcome.InProgress]; nur ein Caller pro Lease darf
 * den Provider aufrufen. [reclaimExpired] räumt abgestürzte Caller
 * auf, indem die Pending-Lease in eine
 * [AiToolOutcome.FailedRetryable] mit
 * [ToolErrorCode.OPERATION_TIMEOUT] umgewandelt wird — der nächste
 * Acquire darf dann eine neue Lease ausstellen.
 *
 * Concurrency: alle Mutationen laufen über
 * [java.util.concurrent.ConcurrentHashMap.compute] /
 * [java.util.concurrent.ConcurrentHashMap.computeIfPresent], die
 * pro Schlüssel atomar sind. Die einzige Außenseiter-Operation
 * ([reclaimExpired]) iteriert read-only und nutzt
 * `computeIfPresent` für die einzelnen Übergänge — kein globales
 * Lock.
 *
 * @param defaultLeaseDuration nur als Default für
 *   [AiToolOutcomeStore.acquire] vorgesehen, wenn der Caller keinen
 *   eigenen Wert übergibt. Tests können einen kleinen Wert (etwa
 *   `Duration.ofSeconds(2)`) injizieren, um Reclaim-Pfade zu
 *   pinnen.
 * @param claimIdFactory injizierbarer Generator für
 *   [AiToolClaimId]-Werte. Production = UUID; Tests pinnen einen
 *   deterministischen Generator, um Goldens zu stabilisieren.
 */
internal class InProcessAiToolOutcomeStore(
    @Suppress("UnusedPrivateProperty") private val defaultLeaseDuration: Duration =
        Duration.ofSeconds(60),
    private val claimIdFactory: () -> AiToolClaimId = { AiToolClaimId(UUID.randomUUID().toString()) },
) : AiToolOutcomeStore {

    private val entries = ConcurrentHashMap<AiToolScope, AiToolOutcome>()

    override fun acquire(
        scope: AiToolScope,
        payloadFingerprint: String,
        leaseDuration: Duration,
        now: Instant,
    ): AiToolAcquireOutcome {
        require(payloadFingerprint.isNotBlank()) {
            "payloadFingerprint must not be blank"
        }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) {
            "leaseDuration must be positive"
        }
        var outcome: AiToolAcquireOutcome? = null
        entries.compute(scope) { _, existing ->
            val (next, oc) = transitionOnAcquire(scope, payloadFingerprint, leaseDuration, now, existing)
            outcome = oc
            next
        }
        return outcome!!
    }

    override fun commit(
        scope: AiToolScope,
        claimId: AiToolClaimId,
        outcome: AiToolOutcome,
        now: Instant,
    ): Boolean {
        require(outcome !is AiToolOutcome.Pending) {
            "commit value must not be Pending"
        }
        require(outcome.scope == scope) {
            "outcome.scope must match the commit scope"
        }
        var transitioned = false
        entries.computeIfPresent(scope) { _, existing ->
            if (existing is AiToolOutcome.Pending &&
                existing.claimId == claimId &&
                existing.payloadFingerprint == outcome.payloadFingerprint
            ) {
                transitioned = true
                outcome
            } else {
                existing
            }
        }
        return transitioned
    }

    override fun reclaimExpired(now: Instant): Int {
        var count = 0
        for ((scope, _) in entries.toMap()) {
            entries.computeIfPresent(scope) { _, existing ->
                if (existing is AiToolOutcome.Pending && !existing.leaseExpiresAt.isAfter(now)) {
                    count++
                    AiToolOutcome.FailedRetryable(
                        scope = existing.scope,
                        payloadFingerprint = existing.payloadFingerprint,
                        toolErrorCode = ToolErrorCode.OPERATION_TIMEOUT,
                        scrubbedMessage = "claim lease expired without commit",
                        attemptCount = existing.attemptCount,
                        lastAttemptAt = existing.leaseExpiresAt,
                    )
                } else {
                    existing
                }
            }
        }
        return count
    }

    private fun transitionOnAcquire(
        scope: AiToolScope,
        payloadFingerprint: String,
        leaseDuration: Duration,
        now: Instant,
        existing: AiToolOutcome?,
    ): Pair<AiToolOutcome, AiToolAcquireOutcome> {
        if (existing != null && existing.payloadFingerprint != payloadFingerprint) {
            return existing to AiToolAcquireOutcome.Conflict(
                scope = scope,
                existingFingerprint = existing.payloadFingerprint,
            )
        }
        return when (existing) {
            null -> freshClaim(scope, payloadFingerprint, leaseDuration, now, attemptCount = 1)
            is AiToolOutcome.Succeeded -> existing to AiToolAcquireOutcome.Existing(scope, existing)
            is AiToolOutcome.FailedTerminal -> existing to AiToolAcquireOutcome.Existing(scope, existing)
            is AiToolOutcome.FailedRetryable -> freshClaim(
                scope, payloadFingerprint, leaseDuration, now,
                attemptCount = existing.attemptCount + 1,
            )
            is AiToolOutcome.Pending -> if (existing.leaseExpiresAt.isAfter(now)) {
                existing to AiToolAcquireOutcome.InProgress(scope, existing.leaseExpiresAt)
            } else {
                // Lease abgelaufen — reclaim: eine neue Lease an
                // den jetzigen Aufrufer mit nächstem
                // attemptCount.
                freshClaim(
                    scope, payloadFingerprint, leaseDuration, now,
                    attemptCount = existing.attemptCount + 1,
                )
            }
        }
    }

    private fun freshClaim(
        scope: AiToolScope,
        payloadFingerprint: String,
        leaseDuration: Duration,
        now: Instant,
        attemptCount: Int,
    ): Pair<AiToolOutcome, AiToolAcquireOutcome> {
        val claimId = claimIdFactory()
        val expiresAt = now.plus(leaseDuration)
        val pending = AiToolOutcome.Pending(
            scope = scope,
            payloadFingerprint = payloadFingerprint,
            claimId = claimId,
            leaseExpiresAt = expiresAt,
            attemptCount = attemptCount,
        )
        return pending to AiToolAcquireOutcome.Acquired(
            scope = scope,
            claimId = claimId,
            leaseExpiresAt = expiresAt,
            attemptCount = attemptCount,
        )
    }
}
