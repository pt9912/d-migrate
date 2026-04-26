package dev.dmigrate.server.application.quota

import dev.dmigrate.server.ports.quota.QuotaDimension
import dev.dmigrate.server.ports.quota.QuotaOutcome

/**
 * Wire-safe projection of [QuotaOutcome.RateLimited]. The internal
 * outcome carries the full [dev.dmigrate.server.ports.quota.QuotaKey]
 * (with `tenantId` and optional `principalId`); this projection drops
 * those identity fields so the AP 6.7 error mapper can serialize the
 * detail without leaking other tenants' identifiers (`§4.7` invariant).
 *
 * The remaining fields (`dimension`, `current`, `limit`) describe the
 * caller's own tenant, so they are safe to include.
 */
data class RateLimitedDetail(
    val dimension: QuotaDimension,
    val current: Long,
    val limit: Long,
) {
    companion object {
        fun from(outcome: QuotaOutcome.RateLimited): RateLimitedDetail =
            RateLimitedDetail(
                dimension = outcome.key.dimension,
                current = outcome.current,
                limit = outcome.limit,
            )
    }
}
