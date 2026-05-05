package dev.dmigrate.server.ports

import dev.dmigrate.server.core.idempotency.IdempotencyClaimOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyReserveOutcome
import dev.dmigrate.server.core.idempotency.IdempotencyScope
import dev.dmigrate.server.core.idempotency.InitResumeOutcome
import dev.dmigrate.server.core.idempotency.InitResumeScope
import java.time.Instant

/**
 * Atomic idempotency reservation store. Implementations must guarantee that
 * concurrent identical reservations on the same scope produce exactly one
 * `Reserved` outcome and return `ExistingPending` for the others until the
 * lease expires or the entry transitions to a terminal state.
 *
 * State machine: `PENDING` -> (`AWAITING_APPROVAL` -> )? `COMMITTED` | `DENIED`.
 * Lease/recovery semantics are documented in `docs/ImpPlan-0.9.6-A.md`
 * §6.2 / §14.2.
 */
interface IdempotencyStore {

    fun reserve(
        scope: IdempotencyScope,
        payloadFingerprint: String,
        now: Instant,
    ): IdempotencyReserveOutcome

    fun reserveInitResume(
        scope: InitResumeScope,
        payloadFingerprint: String,
        sessionId: String,
        now: Instant,
    ): InitResumeOutcome

    fun markAwaitingApproval(scope: IdempotencyScope, now: Instant): Boolean

    /**
     * Atomically transitions an `AWAITING_APPROVAL` entry to `PENDING`
     * so the caller may perform the side-effect (job creation) exactly
     * once. Concurrent claimers on the same scope: one wins
     * (`Claimed`), the rest see `AlreadyClaimed`. Already-committed
     * entries return `Committed` for dedup; denied entries return
     * `Denied`. Anything else (no entry, expired AWAITING_APPROVAL,
     * still PENDING from the original reserve) returns
     * `NotAwaitingApproval`.
     */
    fun claimApproved(scope: IdempotencyScope, now: Instant): IdempotencyClaimOutcome

    /**
     * Transitions a `PENDING` or `AWAITING_APPROVAL` entry to `COMMITTED`.
     *
     * Phase E §7.2: when committing as part of a Job-Start, the resulting
     * COMMITTED-retention MUST cover the job's retention so a deduplicated
     * `COMMITTED` answer is still observable while the job exists. Pass
     * [retentionUntil] = `jobRecord.expiresAt` from the
     * [JobStartTransaction]. The store MUST honor `max(default, retentionUntil)`.
     *
     * For non-job callers (synchronous tools without a job record),
     * [retentionUntil] = `null` keeps the store's default retention.
     *
     * @return `true` if the transition happened, `false` if the entry
     *   was missing or in a non-eligible state.
     */
    fun commit(
        scope: IdempotencyScope,
        resultRef: String,
        now: Instant,
        retentionUntil: Instant? = null,
    ): Boolean

    /**
     * Phase E §7.5: transitioniert eine `PENDING`- oder
     * `AWAITING_APPROVAL`-Reservierung in `DENIED`. Die Retention bestimmt
     * der Store; der zurueckgegebene [Instant] (`null` bei Nicht-Anwendbar)
     * ist `expiresAt` des neuen Eintrags und MUSS vom Caller fuer das
     * `POLICY_DENIED`-Wire-Outcome (`denialExpiresAt`) uebernommen werden.
     */
    fun deny(scope: IdempotencyScope, reason: String, now: Instant): Instant?

    /**
     * Phase E §5.2 / §7.3: transitioniert eine `PENDING`- oder
     * `AWAITING_APPROVAL`-Reservierung in den finalen [IdempotencyState.FAILED]-
     * Zustand. Im Gegensatz zu [deny] (explizite Policy-Ablehnung)
     * ist FAILED für endgültige technische Fehler vorgesehen
     * (Resource-/Tenant-/Validation-Fehler nach Ref-Lookup, nicht-
     * retrybare Materialisierungsfehler). Identische Scope/Fingerprint-
     * Retries liefern deterministisch dasselbe Fehler-Outcome bis
     * `retentionUntil` bzw. zum Default-Retention-Ende.
     *
     * Plan §5.2: FAILED ist final und darf NICHT für abgelaufene
     * `PENDING`-Leases oder `AWAITING_APPROVAL`-Challenges verwendet
     * werden — diese erlauben eine Recovery-Reservierung mit identischem
     * Fingerprint.
     *
     * @return `true` wenn die Transition stattfand; `false` wenn der
     *   Eintrag fehlt oder bereits in einem anderen finalen Zustand
     *   (`COMMITTED`/`DENIED`/`FAILED`) ist.
     */
    fun markFailed(
        scope: IdempotencyScope,
        reason: String,
        now: Instant,
        retentionUntil: Instant? = null,
    ): Boolean

    fun cleanupExpired(now: Instant): Int
}
