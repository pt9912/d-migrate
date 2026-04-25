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
