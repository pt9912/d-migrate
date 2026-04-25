package dev.dmigrate.server.ports

import dev.dmigrate.server.core.idempotency.SyncEffectReserveOutcome
import dev.dmigrate.server.core.idempotency.SyncEffectScope
import java.time.Instant

/**
 * Idempotency store variant for synchronous policy-bound side effects keyed
 * by `approvalKey`. Lets sync tools (e.g., upload-init,
 * provider invocations) be retry-safe under approval semantics.
 */
interface SyncEffectIdempotencyStore {

    fun reserve(
        scope: SyncEffectScope,
        payloadFingerprint: String,
        now: Instant,
    ): SyncEffectReserveOutcome

    fun commit(scope: SyncEffectScope, resultRef: String, now: Instant): Boolean

    fun cleanupExpired(now: Instant): Int
}
