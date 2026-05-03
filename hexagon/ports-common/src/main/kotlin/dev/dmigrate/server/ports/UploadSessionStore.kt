package dev.dmigrate.server.ports

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.upload.FinalizationOutcome
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

    /**
     * AP 6.22: atomic compare-and-set claim of an `ACTIVE` session
     * for finalisation. On success the session is moved to
     * `FINALIZING` with the supplied claim id and lease, and the
     * post-claim copy is returned in [ClaimOutcome.Acquired].
     *
     * Concurrent callers that lose the race see [ClaimOutcome.AlreadyClaimed]
     * with the live claim id and lease so they can produce a
     * deterministic Busy/Conflict response without scheduling any
     * side effects. A session that is not in `ACTIVE` returns
     * [ClaimOutcome.WrongState]; a missing session returns
     * [ClaimOutcome.NotFound].
     */
    fun tryClaimFinalization(
        tenantId: TenantId,
        uploadSessionId: String,
        claimId: String,
        claimedAt: Instant,
        leaseExpiresAt: Instant,
    ): ClaimOutcome

    /**
     * AP 6.22: atomic compare-and-set reclaim of a `FINALIZING`
     * session whose lease has expired (`finalizingLeaseExpiresAt <
     * [now]`). On success the existing claim is overwritten with the
     * supplied id and lease and the [UploadSession.state] stays
     * `FINALIZING` (the new owner now drives the finalisation toward
     * `COMPLETED`/`ABORTED`).
     *
     * Returns [ClaimOutcome.AlreadyClaimed] when the lease is still
     * live (the existing claim must run to terminal or expire on
     * its own). [ClaimOutcome.WrongState] for any non-`FINALIZING`
     * state — explicitly including `COMPLETED`, where the
     * [FinalizationOutcome] / [UploadSession.finalisedSchemaRef] are
     * authoritative and the AP 6.18 replay path should be used.
     */
    fun reclaimStaleFinalization(
        tenantId: TenantId,
        uploadSessionId: String,
        newClaimId: String,
        claimedAt: Instant,
        leaseExpiresAt: Instant,
        now: Instant,
    ): ClaimOutcome

    /**
     * AP 6.22: persist (or overwrite) the [FinalizationOutcome] for a
     * `FINALIZING` session. The CAS-key is the active claim id —
     * a concurrent finaliser whose claim has been reclaimed must not
     * be able to overwrite the new owner's outcome.
     *
     * Returns [PersistOutcome.Persisted] with the post-update
     * session, [PersistOutcome.ClaimMismatch] when the supplied claim
     * id does not match the live one (lost the claim race),
     * [PersistOutcome.WrongState] when the session is not
     * `FINALIZING` anymore, or [PersistOutcome.NotFound]. The
     * session's [UploadSession.state] is NOT changed by this call —
     * callers chain a separate [transition] to `COMPLETED`/`ABORTED`
     * once the outcome is in.
     */
    fun persistFinalizationOutcome(
        tenantId: TenantId,
        uploadSessionId: String,
        claimId: String,
        outcome: FinalizationOutcome,
        now: Instant,
    ): PersistOutcome

    /**
     * AP 6.22: atomic commit of the successful finalisation — sets
     * [FinalizationOutcome] (status = `SUCCEEDED`) AND
     * [UploadSession.finalisedSchemaRef] AND transitions the state
     * `FINALIZING → COMPLETED` in a single CAS step keyed on the
     * active claim id.
     *
     * The atomicity matters because splitting the three updates into
     * separate calls leaves a race window where a stale-lease
     * reclaim could land between the SUCCEEDED-persist and the
     * COMPLETED-transition; last-writer-wins on the schemaRef-save
     * would then stomp the reclaimer's claim. This call avoids that
     * by gating all three writes on `finalizingClaimId == claimId`.
     *
     * Returns [PersistOutcome.Persisted] with the post-commit
     * session, [PersistOutcome.ClaimMismatch] when the supplied
     * claim id does not match the live one,
     * [PersistOutcome.WrongState] when the session is not in
     * `FINALIZING` anymore (e.g. concurrent abort), or
     * [PersistOutcome.NotFound].
     */
    fun commitFinalization(
        tenantId: TenantId,
        uploadSessionId: String,
        claimId: String,
        outcome: FinalizationOutcome,
        finalisedSchemaRef: String,
        now: Instant,
    ): PersistOutcome
}

sealed interface TransitionOutcome {
    data class Applied(val session: UploadSession) : TransitionOutcome
    data class IllegalTransition(
        val from: UploadSessionState,
        val to: UploadSessionState,
    ) : TransitionOutcome
    data object NotFound : TransitionOutcome
}

sealed interface ClaimOutcome {
    data class Acquired(val session: UploadSession) : ClaimOutcome
    data class AlreadyClaimed(
        val currentClaimId: String,
        val leaseExpiresAt: Instant,
    ) : ClaimOutcome
    data class WrongState(val state: UploadSessionState) : ClaimOutcome
    data object NotFound : ClaimOutcome
}

sealed interface PersistOutcome {
    data class Persisted(val session: UploadSession) : PersistOutcome
    data class ClaimMismatch(val currentClaimId: String?) : PersistOutcome
    data class WrongState(val state: UploadSessionState) : PersistOutcome
    data object NotFound : PersistOutcome
}
