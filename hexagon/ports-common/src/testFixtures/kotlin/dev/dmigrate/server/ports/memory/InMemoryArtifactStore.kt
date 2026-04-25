package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ArtifactStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryArtifactStore : ArtifactStore {

    private data class Key(val tenantId: TenantId, val artifactId: String)

    private val records = ConcurrentHashMap<Key, ArtifactRecord>()

    override fun save(record: ArtifactRecord): ArtifactRecord {
        records[Key(record.tenantId, record.managedArtifact.artifactId)] = record
        return record
    }

    override fun findById(tenantId: TenantId, artifactId: String): ArtifactRecord? =
        records[Key(tenantId, artifactId)]

    override fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId?,
        kindFilter: ArtifactKind?,
    ): PageResult<ArtifactRecord> {
        val matching = records.values
            .filter { it.tenantId == tenantId }
            .filter { ownerFilter == null || it.ownerPrincipalId == ownerFilter }
            .filter { kindFilter == null || it.kind == kindFilter }
            .sortedBy { it.managedArtifact.createdAt }
        return paginate(matching, page)
    }

    override fun deleteExpired(now: Instant): Int {
        val expired = records.entries
            .filter { it.value.managedArtifact.expiresAt.isBefore(now) }
            .map { it.key }
        expired.forEach { records.remove(it) }
        return expired.size
    }
}
