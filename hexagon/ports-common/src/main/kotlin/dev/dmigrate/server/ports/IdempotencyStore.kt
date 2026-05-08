package dev.dmigrate.server.ports

import dev.dmigrate.server.core.approval.ApprovalChallenge
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
 * Lease/recovery semantics are documented in `spec/mcp-server.md`
 * §6.2 / §14.2.
 *
 * Implementoren MUESSEN die `IdempotencyStoreContractTests`-Suite
 * durchlaufen — der Atomicity-Vertrag (parallele identische Reserves
 * liefern exactly-one Reserved; durable Approval-Challenge ueberlebt
 * den `markAwaitingApproval` -> `reserve`-Roundtrip) ist in
 * `spec/phase-e-port-atomicity.md` Abschnitte (1) + (2)
 * dokumentiert.
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

    /**
     * LF-012 / LN-011 / LN-017 / LN-027 (Review-Fix Blocker #3): markAwaitingApproval-Variante,
     * die die durable [ApprovalChallenge] mitspeichert. Beim spaeteren
     * `reserve` (Approved-Retry) liefert die `AwaitingApproval`-Antwort
     * die gespeicherte Challenge zurueck, sodass der Caller den
     * `approvalRequestId`-Anti-Replay-Check echt durchfuehren kann.
     *
     * Default-Argument `challenge = null` haelt Bestands-Caller
     * backward-compat (keine Challenge gespeichert).
     */
    fun markAwaitingApproval(
        scope: IdempotencyScope,
        now: Instant,
        challenge: ApprovalChallenge? = null,
    ): Boolean

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
     * LF-012 / LN-011 / LN-017 / LN-027: when committing as part of a Job-Start, the resulting
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
     * LF-012 / LN-011 / LN-017 / LN-027: transitioniert eine `PENDING`- oder
     * `AWAITING_APPROVAL`-Reservierung in `DENIED`. Die Retention bestimmt
     * der Store; der zurueckgegebene [Instant] (`null` bei Nicht-Anwendbar)
     * ist `expiresAt` des neuen Eintrags und MUSS vom Caller fuer das
     * `POLICY_DENIED`-Wire-Outcome (`denialExpiresAt`) uebernommen werden.
     */
    fun deny(scope: IdempotencyScope, reason: String, now: Instant): Instant?

    /**
     * LF-012 / LN-011 / LN-017 / LN-027: transitioniert eine `PENDING`- oder
     * `AWAITING_APPROVAL`-Reservierung in den finalen [IdempotencyState.FAILED]-
     * Zustand. Im Gegensatz zu [deny] (explizite Policy-Ablehnung)
     * ist FAILED für endgültige technische Fehler vorgesehen
     * (Resource-/Tenant-/Validation-Fehler nach Ref-Lookup, nicht-
     * retrybare Materialisierungsfehler). Identische Scope/Fingerprint-
     * Retries liefern deterministisch dasselbe Fehler-Outcome bis
     * `retentionUntil` bzw. zum Default-Retention-Ende.
     *
     * LF-012 / LN-011 / LN-017 / LN-027: FAILED ist final und darf NICHT für abgelaufene
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
