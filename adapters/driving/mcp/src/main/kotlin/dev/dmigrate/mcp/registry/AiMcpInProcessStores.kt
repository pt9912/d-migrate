package dev.dmigrate.mcp.registry

import dev.dmigrate.server.core.ai.AiArtifactMetadata
import dev.dmigrate.server.core.ai.AiToolAcquireOutcome
import dev.dmigrate.server.core.ai.AiToolClaimId
import dev.dmigrate.server.core.ai.AiToolOutcome
import dev.dmigrate.server.core.ai.AiToolScope
import dev.dmigrate.server.core.error.ToolErrorCode
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.AiArtifactMetadataStore
import dev.dmigrate.server.ports.AiToolOutcomeStore
import dev.dmigrate.server.ports.SaveAiArtifactMetadataOutcome
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — in-process [AiToolOutcomeStore]
 * mit Single-Writer-Lease + Reclaim.
 *
 * LF-017 / LF-024 / LN-030 / LN-031-Anforderung, die dieser Store explizit erfüllt:
 *
 * > "Der bestehende SyncEffectIdempotencyStore reicht unverändert
 * > nicht aus, wenn parallele gleiche Pending-Reserves erneut
 * > Reserved liefern."
 *
 * LF-010 / LF-013 / LN-009 / LN-011's [InProcessSyncEffectIdempotencyStore.reserve] fällt bei
 * einer aktiven Lease und identischem Fingerprint auf
 * `Reserved(existing.expiresAt)` zurück (`InProcessUploadControlStores.kt:51`).
 * Das bleibt für sequenzielle Upload-Init-Pfade passend; KI-Pfade
 * brauchen dagegen Single-Writer-Semantik, damit kein zweiter
 * Provider-Aufruf und kein zweites Artefakt entstehen.
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
                        createdAt = existing.createdAt,
                        updatedAt = now,
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
            is AiToolOutcome.FailedRetryable -> {
                if (existing.canStartNewAttempt()) {
                    freshClaim(
                        scope, payloadFingerprint, leaseDuration, now,
                        attemptCount = existing.attemptCount + 1,
                        previousRetryable = existing,
                    )
                } else {
                    existing to AiToolAcquireOutcome.ExistingRetryable(scope, existing)
                }
            }
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
        previousRetryable: AiToolOutcome.FailedRetryable? = null,
    ): Pair<AiToolOutcome, AiToolAcquireOutcome> {
        val claimId = claimIdFactory()
        val expiresAt = now.plus(leaseDuration)
        val pending = AiToolOutcome.Pending(
            scope = scope,
            payloadFingerprint = payloadFingerprint,
            claimId = claimId,
            leaseExpiresAt = expiresAt,
            attemptCount = attemptCount,
            createdAt = previousRetryable?.createdAt ?: now,
            updatedAt = now,
        )
        return pending to AiToolAcquireOutcome.Acquired(
            scope = scope,
            claimId = claimId,
            leaseExpiresAt = expiresAt,
            attemptCount = attemptCount,
            previousRetryable = previousRetryable,
        )
    }

    private fun AiToolOutcome.FailedRetryable.canStartNewAttempt(): Boolean =
        approvalRequestId != null ||
            (toolErrorCode == ToolErrorCode.OPERATION_TIMEOUT &&
                providerName == null &&
                promptFingerprint == null &&
                scrubbedMessage == "claim lease expired without commit")
}

/**
 * LF-017 / LF-024 / LN-030 / LN-031 — in-process
 * [AiArtifactMetadataStore].
 *
 * Persistiert KI-Artefakt-Metadaten parallel zum
 * [dev.dmigrate.server.ports.ArtifactStore]. LF-017 / LF-024 / LN-030 / LN-031 Z. 748-752:
 *
 * - `save` ist idempotent pro `(tenantId, artifactId)` —
 *   identische Metadaten kollabieren auf
 *   [SaveAiArtifactMetadataOutcome.AlreadyExists] (No-Op-Replay
 *   nach Crash zwischen Provider-Aufruf und Outcome-Commit);
 *   abweichende Metadaten unter demselben Tupel liefern
 *   [SaveAiArtifactMetadataOutcome.Conflict] (Server-State-Drift,
 *   nie an Caller leaken).
 * - `findByArtifactId` und `findByResourceUri` laufen über
 *   getrennte Indexes; `resourceUri.id == artifactId` ist als
 *   `AiArtifactMetadata`-Init-Invariante garantiert, die beiden
 *   Indexes bleiben so im Schritt.
 * - `deleteByArtifactId` ist idempotent (Sweeper-fähig).
 *
 * Concurrency: einzelne Mutationen über
 * [java.util.concurrent.ConcurrentHashMap.compute] sind atomar pro
 * Schlüssel. Der Sekundär-Index ist beim `save`-Erfolg in derselben
 * `compute`-Phase aktualisiert, beim `delete` ebenfalls — keine
 * Race-Condition zwischen Primär- und Sekundär-Lookup.
 */
internal class InProcessAiArtifactMetadataStore : AiArtifactMetadataStore {

    private data class Index(val tenantId: TenantId, val key: String)

    private val byArtifactId = ConcurrentHashMap<Index, AiArtifactMetadata>()
    private val byResourceUri = ConcurrentHashMap<Index, String>()

    override fun save(metadata: AiArtifactMetadata): SaveAiArtifactMetadataOutcome {
        val artifactKey = Index(metadata.tenantId, metadata.artifactId)
        val uriKey = Index(metadata.tenantId, metadata.resourceUri.render())

        var outcome: SaveAiArtifactMetadataOutcome? = null
        byArtifactId.compute(artifactKey) { _, existing ->
            outcome = when {
                existing == null -> {
                    byResourceUri[uriKey] = metadata.artifactId
                    SaveAiArtifactMetadataOutcome.Saved(metadata)
                }
                existing == metadata -> SaveAiArtifactMetadataOutcome.AlreadyExists(existing)
                else -> SaveAiArtifactMetadataOutcome.Conflict(
                    existing = existing,
                    attempted = metadata,
                )
            }
            // Bei Conflict / AlreadyExists: existing-Wert unverändert lassen.
            if (outcome is SaveAiArtifactMetadataOutcome.Saved) metadata else existing
        }
        return outcome!!
    }

    override fun findByArtifactId(tenantId: TenantId, artifactId: String): AiArtifactMetadata? =
        byArtifactId[Index(tenantId, artifactId)]

    override fun findByResourceUri(
        tenantId: TenantId,
        resourceUri: ServerResourceUri,
    ): AiArtifactMetadata? {
        val artifactId = byResourceUri[Index(tenantId, resourceUri.render())] ?: return null
        return byArtifactId[Index(tenantId, artifactId)]
    }

    override fun deleteByArtifactId(tenantId: TenantId, artifactId: String): Boolean {
        val artifactKey = Index(tenantId, artifactId)
        var deleted = false
        byArtifactId.computeIfPresent(artifactKey) { _, existing ->
            byResourceUri.remove(Index(tenantId, existing.resourceUri.render()))
            deleted = true
            null
        }
        return deleted
    }
}
