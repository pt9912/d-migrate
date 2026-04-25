package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import dev.dmigrate.server.core.upload.UploadSessionTransitions
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

    override fun expireDue(now: Instant): Int {
        var expired = 0
        sessions.replaceAll { _, session ->
            if (session.state == UploadSessionState.ACTIVE &&
                (session.idleTimeoutAt.isBefore(now) || session.absoluteLeaseExpiresAt.isBefore(now))
            ) {
                expired++
                session.copy(state = UploadSessionState.EXPIRED, updatedAt = now)
            } else {
                session
            }
        }
        return expired
    }
}
