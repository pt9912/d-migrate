package dev.dmigrate.server.ports.quota

import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

enum class QuotaDimension {
    ACTIVE_JOBS,
    ACTIVE_UPLOAD_SESSIONS,
    UPLOAD_BYTES,
    PARALLEL_SEGMENT_WRITES,
    PROVIDER_CALLS,
}

/**
 * Identifies a quota counter slot. The §4.7 plan demands "active jobs
 * per tenant/principal **and operation**", so [operation] participates
 * in the key alongside the optional [principalId]. `null` means
 * tenant-scoped (or tenant+principal-scoped) without per-operation
 * partitioning; non-null applies the limit per `(tenant, principal?,
 * operation)` triple.
 */
data class QuotaKey(
    val tenantId: TenantId,
    val dimension: QuotaDimension,
    val principalId: PrincipalId? = null,
    val operation: String? = null,
)

data class QuotaCounter(
    val key: QuotaKey,
    val current: Long,
    val limit: Long,
)

sealed interface QuotaOutcome {
    val key: QuotaKey
    val amount: Long

    data class Granted(
        override val key: QuotaKey,
        override val amount: Long,
        val newCurrent: Long,
        val limit: Long,
    ) : QuotaOutcome

    data class RateLimited(
        override val key: QuotaKey,
        override val amount: Long,
        val current: Long,
        val limit: Long,
    ) : QuotaOutcome
}

/**
 * Tracks raw quota counters. The application-layer `QuotaService` (AP 6.6)
 * wraps reserve/commit/release/refund semantics on top of these primitives.
 */
interface QuotaStore {

    fun reserve(key: QuotaKey, amount: Long, limit: Long): QuotaOutcome

    fun release(key: QuotaKey, amount: Long): Long

    fun current(key: QuotaKey): Long
}
