package dev.dmigrate.server.ports

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * Phase-D §6.3 + §10.4 filter for `artifact_list`. Time window is
 * inclusive at both ends; `jobRef` matches `ArtifactRecord.jobRef`
 * exactly so a Phase-D client can fan out artefacts of one job.
 */
data class ArtifactListFilter(
    val ownerFilter: PrincipalId? = null,
    val kindFilter: ArtifactKind? = null,
    val jobRef: String? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
)

interface ArtifactStore {

    fun save(record: ArtifactRecord): ArtifactRecord

    fun findById(tenantId: TenantId, artifactId: String): ArtifactRecord?

    fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId? = null,
        kindFilter: ArtifactKind? = null,
    ): PageResult<ArtifactRecord>

    /**
     * Phase-D filtered list. Default sort:
     *   1. `managedArtifact.createdAt` DESC
     *   2. `managedArtifact.artifactId` ASC (stable id tiebreaker)
     */
    fun list(
        tenantId: TenantId,
        filter: ArtifactListFilter,
        page: PageRequest,
    ): PageResult<ArtifactRecord>

    fun deleteExpired(now: Instant): Int
}
