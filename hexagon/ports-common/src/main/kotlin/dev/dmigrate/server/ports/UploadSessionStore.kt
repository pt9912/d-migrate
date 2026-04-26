package dev.dmigrate.server.ports

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.UploadSession
import dev.dmigrate.server.core.upload.UploadSessionState
import java.time.Instant

interface UploadSessionStore {

    fun save(session: UploadSession): UploadSession

    fun findById(tenantId: TenantId, uploadSessionId: String): UploadSession?

    fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId? = null,
        stateFilter: UploadSessionState? = null,
    ): PageResult<UploadSession>

    fun transition(
        tenantId: TenantId,
        uploadSessionId: String,
        newState: UploadSessionState,
        now: Instant,
    ): TransitionOutcome

    /**
     * Transitions every ACTIVE session whose `idleTimeoutAt` or
     * `absoluteLeaseExpiresAt` is before [now] to `EXPIRED`. Returns
     * the affected sessions in their post-transition form so callers
     * (the `UploadSessionService`) can drive segment cleanup per
     * session.
     */
    fun expireDue(now: Instant): List<UploadSession>
}

sealed interface TransitionOutcome {
    data class Applied(val session: UploadSession) : TransitionOutcome
    data class IllegalTransition(
        val from: UploadSessionState,
        val to: UploadSessionState,
    ) : TransitionOutcome
    data object NotFound : TransitionOutcome
}
