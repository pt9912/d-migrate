package dev.dmigrate.server.core.idempotency

import java.time.Instant

sealed interface IdempotencyReserveOutcome {
    val scope: IdempotencyScope

    data class Reserved(
        override val scope: IdempotencyScope,
        val leaseExpiresAt: Instant,
    ) : IdempotencyReserveOutcome

    data class ExistingPending(
        override val scope: IdempotencyScope,
        val leaseExpiresAt: Instant,
    ) : IdempotencyReserveOutcome

    data class AwaitingApproval(
        override val scope: IdempotencyScope,
        val expiresAt: Instant,
    ) : IdempotencyReserveOutcome

    data class Committed(
        override val scope: IdempotencyScope,
        val resultRef: String,
    ) : IdempotencyReserveOutcome

    data class Denied(
        override val scope: IdempotencyScope,
        val expiresAt: Instant,
        val reason: String,
    ) : IdempotencyReserveOutcome

    data class Conflict(
        override val scope: IdempotencyScope,
        val existingFingerprint: String,
    ) : IdempotencyReserveOutcome
}

sealed interface SyncEffectReserveOutcome {
    val scope: SyncEffectScope

    data class Reserved(
        override val scope: SyncEffectScope,
        val leaseExpiresAt: Instant,
    ) : SyncEffectReserveOutcome

    data class Existing(
        override val scope: SyncEffectScope,
        val resultRef: String,
    ) : SyncEffectReserveOutcome

    data class Conflict(
        override val scope: SyncEffectScope,
        val existingFingerprint: String,
    ) : SyncEffectReserveOutcome
}

/**
 * Outcome of `IdempotencyStore.claimApproved(scope, now)` — the atomic
 * AWAITING_APPROVAL -> PENDING transition the §6.2 plan demands so
 * exactly one caller is allowed to perform side-effects after a policy
 * approval.
 */
sealed interface IdempotencyClaimOutcome {
    val scope: IdempotencyScope

    data class Claimed(
        override val scope: IdempotencyScope,
        val leaseExpiresAt: Instant,
    ) : IdempotencyClaimOutcome

    data class AlreadyClaimed(
        override val scope: IdempotencyScope,
        val leaseExpiresAt: Instant,
    ) : IdempotencyClaimOutcome

    data class Committed(
        override val scope: IdempotencyScope,
        val resultRef: String,
    ) : IdempotencyClaimOutcome

    data class Denied(
        override val scope: IdempotencyScope,
        val reason: String,
    ) : IdempotencyClaimOutcome

    data class NotAwaitingApproval(override val scope: IdempotencyScope) : IdempotencyClaimOutcome
}

sealed interface InitResumeOutcome {
    val scope: InitResumeScope

    data class Reserved(
        override val scope: InitResumeScope,
        val sessionId: String,
        val expiresAt: Instant,
    ) : InitResumeOutcome

    data class Existing(
        override val scope: InitResumeScope,
        val sessionId: String,
        val expiresAt: Instant,
    ) : InitResumeOutcome

    data class Conflict(
        override val scope: InitResumeScope,
        val existingFingerprint: String,
    ) : InitResumeOutcome
}
