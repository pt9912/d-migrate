package dev.dmigrate.server.ports

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Instant

data class DiffIndexEntry(
    val diffId: String,
    val tenantId: TenantId,
    val resourceUri: ServerResourceUri,
    val artifactRef: String,
    /**
     * Plan-D §6.4 wire-name `leftSchemaId` (kept as
     * `sourceRef` here for backward-compatibility with existing
     * stage helpers; the projector renders it as
     * `leftSchemaId`).
     */
    val sourceRef: String,
    /**
     * Plan-D §6.4 wire-name `rightSchemaId`. See [sourceRef].
     */
    val targetRef: String,
    val displayName: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val jobRef: String? = null,
    val labels: Map<String, String> = emptyMap(),
    /**
     * Plan-D §6.4 mindestfeld `statusSummary`: short canonical
     * outcome label (e.g. `IDENTICAL`, `DIFF_PRESENT`,
     * `INCOMPATIBLE`). Null when the producer did not record
     * one.
     */
    val statusSummary: String? = null,
)

/**
 * Phase-D §6.3 + §10.4 filter for `diff_list`. `sourceRef` /
 * `targetRef` match the `DiffIndexEntry.sourceRef` /
 * `DiffIndexEntry.targetRef` exactly so a Phase-D client can find
 * every diff for a given schema pair; time window inclusive.
 */
data class DiffListFilter(
    val jobRef: String? = null,
    val sourceRef: String? = null,
    val targetRef: String? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
)

interface DiffStore {

    fun save(entry: DiffIndexEntry): DiffIndexEntry

    fun findById(tenantId: TenantId, diffId: String): DiffIndexEntry?

    fun list(tenantId: TenantId, page: PageRequest): PageResult<DiffIndexEntry>

    /**
     * Phase-D filtered list. Default sort:
     *   1. `createdAt` DESC
     *   2. `diffId` ASC
     */
    fun list(
        tenantId: TenantId,
        filter: DiffListFilter,
        page: PageRequest,
    ): PageResult<DiffIndexEntry>

    fun deleteExpired(now: Instant): Int
}
