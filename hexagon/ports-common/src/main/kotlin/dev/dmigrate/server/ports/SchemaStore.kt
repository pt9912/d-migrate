package dev.dmigrate.server.ports

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri
import java.time.Instant

/**
 * Tenant-scoped metadata index for schema artefacts. Stays free of
 * `hexagon:profiling`/`hexagon:core.model.SchemaDefinition` references —
 * typed schema reads remain the domain of the reverse engineering modules.
 */
data class SchemaIndexEntry(
    val schemaId: String,
    val tenantId: TenantId,
    val resourceUri: ServerResourceUri,
    val artifactRef: String,
    val displayName: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val jobRef: String? = null,
    val labels: Map<String, String> = emptyMap(),
)

interface SchemaStore {

    fun save(entry: SchemaIndexEntry): SchemaIndexEntry

    fun findById(tenantId: TenantId, schemaId: String): SchemaIndexEntry?

    fun list(tenantId: TenantId, page: PageRequest): PageResult<SchemaIndexEntry>

    fun deleteExpired(now: Instant): Int
}
