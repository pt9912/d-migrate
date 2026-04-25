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
    val sourceRef: String,
    val targetRef: String,
    val displayName: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val jobRef: String? = null,
    val labels: Map<String, String> = emptyMap(),
)

interface DiffStore {

    fun save(entry: DiffIndexEntry): DiffIndexEntry

    fun findById(tenantId: TenantId, diffId: String): DiffIndexEntry?

    fun list(tenantId: TenantId, page: PageRequest): PageResult<DiffIndexEntry>

    fun deleteExpired(now: Instant): Int
}
