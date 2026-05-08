package dev.dmigrate.server.ports

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Instant

/**
 * Tenant-scoped metadata index for profile artefacts. Per
 * `spec/mcp-server.md` §5.3 this port intentionally does not reference
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
    /**
     * LF-012 / LN-038: identifies the connection the profile was
     * generated from. Either a bare `connectionId` (LF-012 / LN-011 / LN-017 / LN-027
     * start tools fill it from the connection-ref store) or a
     * full `connectionResourceUri`. Null means the producer did
     * not record one.
     */
    val connectionRef: String? = null,
    /**
     * LF-012 / LN-038: profiling scope (e.g. `full`, `tables-only`,
     * a comma-separated table allowlist). Operator-supplied —
     * LF-012 / LN-038 explicitly excludes raw paths / connection
     * URLs / ENV expansions, but Profile-Scope strings are
     * already metadata that survives scrubbing safely.
     */
    val scope: String? = null,
    /**
     * LF-012 / LN-038 optional `warningCount` — cumulative profiling
     * warnings the producer raised. Null when not recorded.
     */
    val warningCount: Int? = null,
)

/**
 * LF-012 / LN-038 filter for `profile_list`. `jobRef` ties
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
     * LF-012 / LN-038 filtered list. Default sort:
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
