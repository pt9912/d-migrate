package dev.dmigrate.server.ports

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Instant

/**
 * Tenant-scoped metadata index for profile artefacts. Per
 * `docs/ImpPlan-0.9.6-A.md` §5.3 this port intentionally does not reference
 * `hexagon:profiling` types; typed profile projections (e.g. `DatabaseProfile`)
 * stay within the profiling module.
 */
data class ProfileIndexEntry(
    val profileId: String,
    val tenantId: TenantId,
    val resourceUri: ServerResourceUri,
    val artifactRef: String,
    val displayName: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val jobRef: String? = null,
    val labels: Map<String, String> = emptyMap(),
)

/**
 * Phase-D §6.3 + §10.4 filter for `profile_list`. `jobRef` ties
 * profiles to a producing job when the profile was emitted by one;
 * time window inclusive at both ends.
 */
data class ProfileListFilter(
    val jobRef: String? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
)

interface ProfileStore {

    fun save(entry: ProfileIndexEntry): ProfileIndexEntry

    fun findById(tenantId: TenantId, profileId: String): ProfileIndexEntry?

    fun list(tenantId: TenantId, page: PageRequest): PageResult<ProfileIndexEntry>

    /**
     * Phase-D filtered list. Default sort:
     *   1. `createdAt` DESC
     *   2. `profileId` ASC
     */
    fun list(
        tenantId: TenantId,
        filter: ProfileListFilter,
        page: PageRequest,
    ): PageResult<ProfileIndexEntry>

    fun deleteExpired(now: Instant): Int
}
