package dev.dmigrate.server.ports

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

interface ArtifactStore {

    fun save(record: ArtifactRecord): ArtifactRecord

    fun findById(tenantId: TenantId, artifactId: String): ArtifactRecord?

    fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId? = null,
        kindFilter: ArtifactKind? = null,
    ): PageResult<ArtifactRecord>

    fun deleteExpired(now: Instant): Int
}
