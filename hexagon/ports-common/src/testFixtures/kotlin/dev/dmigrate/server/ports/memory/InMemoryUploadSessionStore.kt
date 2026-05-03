package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.FinalizationOutcome
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.core.upload.UploadSessionTransitions
import dev.dmigrate.server.ports.ClaimOutcome
import dev.dmigrate.server.ports.PersistOutcome
import dev.dmigrate.server.ports.TransitionOutcome
import dev.dmigrate.server.ports.UploadSessionStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryUploadSessionStore : UploadSessionStore {

    private data class Key(val tenantId: TenantId, val sessionId: String)

    private val sessions = ConcurrentHashMap<Key, UploadSession>()

    override fun save(session: UploadSession): UploadSession {
        sessions[Key(session.tenantId, session.uploadSessionId)] = session
        return session
    }

    override fun findById(tenantId: TenantId, uploadSessionId: String): UploadSession? =
        sessions[Key(tenantId, uploadSessionId)]

    override fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId?,
        stateFilter: UploadSessionState?,
    ): PageResult<UploadSession> {
        val matching = sessions.values
            .filter { it.tenantId == tenantId }
            .filter { ownerFilter == null || it.ownerPrincipalId == ownerFilter }
            .filter { stateFilter == null || it.state == stateFilter }
            .sortedBy { it.createdAt }
        return paginate(matching, page)
    }

    override fun transition(
        tenantId: TenantId,
        uploadSessionId: String,
        newState: UploadSessionState,
        now: Instant,
    ): TransitionOutcome {
        val key = Key(tenantId, uploadSessionId)
        var outcome: TransitionOutcome? = null
        sessions.compute(key) { _, existing ->
            when {
                existing == null -> {
                    outcome = TransitionOutcome.NotFound
                    null
                }
                !UploadSessionTransitions.isAllowed(existing.state, newState) -> {
                    outcome = TransitionOutcome.IllegalTransition(existing.state, newState)
                    existing
                }
                else -> {
                    val updated = existing.copy(state = newState, updatedAt = now)
                    outcome = TransitionOutcome.Applied(updated)
                    updated
                }
            }
        }
        return outcome ?: TransitionOutcome.NotFound
    }

    override fun expireDue(now: Instant): List<UploadSession> {
        val expired = mutableListOf<UploadSession>()
        sessions.replaceAll { _, session ->
            if (session.state == UploadSessionState.ACTIVE &&
                (session.idleTimeoutAt.isBefore(now) || session.absoluteLeaseExpiresAt.isBefore(now))
            ) {
                val transitioned = session.copy(state = UploadSessionState.EXPIRED, updatedAt = now)
                expired.add(transitioned)
                transitioned
            } else {
                session
            }
        }
        return expired
    }

    override fun tryClaimFinalization(
        tenantId: TenantId,
        uploadSessionId: String,
        claimId: String,
        claimedAt: Instant,
        leaseExpiresAt: Instant,
    ): ClaimOutcome {
        val key = Key(tenantId, uploadSessionId)
        var outcome: ClaimOutcome? = null
        sessions.compute(key) { _, existing ->
            if (existing == null) {
                outcome = ClaimOutcome.NotFound
                return@compute null
            }
            when (existing.state) {
                UploadSessionState.ACTIVE -> {
                    val claimed = existing.copy(
                        state = UploadSessionState.FINALIZING,
                        updatedAt = claimedAt,
                        finalizingClaimId = claimId,
                        finalizingClaimedAt = claimedAt,
                        finalizingLeaseExpiresAt = leaseExpiresAt,
                    )
                    outcome = ClaimOutcome.Acquired(claimed)
                    claimed
                }
                UploadSessionState.FINALIZING -> {
                    outcome = ClaimOutcome.AlreadyClaimed(
                        currentClaimId = existing.finalizingClaimId
                            ?: error("FINALIZING session ${existing.uploadSessionId} has null claim id"),
                        leaseExpiresAt = existing.finalizingLeaseExpiresAt
                            ?: error("FINALIZING session ${existing.uploadSessionId} has null lease"),
                    )
                    existing
                }
                else -> {
                    outcome = ClaimOutcome.WrongState(existing.state)
                    existing
                }
            }
        }
        return outcome ?: ClaimOutcome.NotFound
    }

    override fun reclaimStaleFinalization(
        tenantId: TenantId,
        uploadSessionId: String,
        newClaimId: String,
        claimedAt: Instant,
        leaseExpiresAt: Instant,
        now: Instant,
    ): ClaimOutcome {
        val key = Key(tenantId, uploadSessionId)
        var outcome: ClaimOutcome? = null
        sessions.compute(key) { _, existing ->
            if (existing == null) {
                outcome = ClaimOutcome.NotFound
                return@compute null
            }
            if (existing.state != UploadSessionState.FINALIZING) {
                outcome = ClaimOutcome.WrongState(existing.state)
                return@compute existing
            }
            val storedExpiry = existing.finalizingLeaseExpiresAt
                ?: error("FINALIZING session ${existing.uploadSessionId} has null lease")
            // §6.22: negative clock jumps must NOT extend the stored
            // lease. Only reclaim when the stored expiry is strictly
            // before `now`; equal `now` keeps the existing claim safe.
            if (!storedExpiry.isBefore(now)) {
                outcome = ClaimOutcome.AlreadyClaimed(
                    currentClaimId = existing.finalizingClaimId
                        ?: error("FINALIZING session ${existing.uploadSessionId} has null claim id"),
                    leaseExpiresAt = storedExpiry,
                )
                existing
            } else {
                val reclaimed = existing.copy(
                    updatedAt = claimedAt,
                    finalizingClaimId = newClaimId,
                    finalizingClaimedAt = claimedAt,
                    finalizingLeaseExpiresAt = leaseExpiresAt,
                )
                outcome = ClaimOutcome.Acquired(reclaimed)
                reclaimed
            }
        }
        return outcome ?: ClaimOutcome.NotFound
    }

    override fun persistFinalizationOutcome(
        tenantId: TenantId,
        uploadSessionId: String,
        claimId: String,
        outcome: FinalizationOutcome,
        now: Instant,
    ): PersistOutcome {
        val key = Key(tenantId, uploadSessionId)
        var result: PersistOutcome? = null
        sessions.compute(key) { _, existing ->
            if (existing == null) {
                result = PersistOutcome.NotFound
                return@compute null
            }
            if (existing.state != UploadSessionState.FINALIZING) {
                result = PersistOutcome.WrongState(existing.state)
                return@compute existing
            }
            if (existing.finalizingClaimId != claimId) {
                result = PersistOutcome.ClaimMismatch(existing.finalizingClaimId)
                return@compute existing
            }
            val updated = existing.copy(
                updatedAt = now,
                finalizationOutcome = outcome,
            )
            result = PersistOutcome.Persisted(updated)
            updated
        }
        return result ?: PersistOutcome.NotFound
    }

    override fun commitFinalization(
        tenantId: TenantId,
        uploadSessionId: String,
        claimId: String,
        outcome: FinalizationOutcome,
        finalisedSchemaRef: String,
        now: Instant,
    ): PersistOutcome {
        val key = Key(tenantId, uploadSessionId)
        var result: PersistOutcome? = null
        sessions.compute(key) { _, existing ->
            if (existing == null) {
                result = PersistOutcome.NotFound
                return@compute null
            }
            if (existing.state != UploadSessionState.FINALIZING) {
                result = PersistOutcome.WrongState(existing.state)
                return@compute existing
            }
            if (existing.finalizingClaimId != claimId) {
                result = PersistOutcome.ClaimMismatch(existing.finalizingClaimId)
                return@compute existing
            }
            val updated = existing.copy(
                state = UploadSessionState.COMPLETED,
                updatedAt = now,
                finalizationOutcome = outcome,
                finalisedSchemaRef = finalisedSchemaRef,
            )
            result = PersistOutcome.Persisted(updated)
            updated
        }
        return result ?: PersistOutcome.NotFound
    }
}
