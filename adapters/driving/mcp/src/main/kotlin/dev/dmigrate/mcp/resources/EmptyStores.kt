package dev.dmigrate.mcp.resources

import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ArtifactListFilter
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.WriteArtifactOutcome
import dev.dmigrate.server.ports.ConnectionReferenceStore
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.DiffListFilter
import dev.dmigrate.server.ports.DiffStore
import dev.dmigrate.server.ports.JobListFilter
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.ProfileListFilter
import dev.dmigrate.server.ports.ProfileStore
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaListFilter
import dev.dmigrate.server.ports.SchemaStore
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant

/**
 * No-op store implementations used by [ResourceStores.empty] and the
 * Phase B "transport-only" bootstrap default. Every list returns
 * `PageResult(items=[], nextPageToken=null)`. Save/find/delete are
 * not used by `resources/list` and either return their argument or
 * `null`/`false` — the bootstrap never invokes them.
 *
 * These exist purely so the route/bootstrap can stand up without a
 * real backend; Phase C/D wires real stores at startup.
 */
internal object EmptyJobStore : JobStore {
    override fun save(record: JobRecord): JobRecord = record
    override fun findById(tenantId: TenantId, jobId: String): JobRecord? = null
    override fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId?,
    ): PageResult<JobRecord> = PageResult(emptyList(), null)
    override fun list(
        tenantId: TenantId,
        filter: JobListFilter,
        page: PageRequest,
    ): PageResult<JobRecord> = PageResult(emptyList(), null)
    override fun deleteExpired(now: Instant): Int = 0
}

internal object EmptyArtifactStore : ArtifactStore {
    override fun save(record: ArtifactRecord): ArtifactRecord = record
    override fun findById(tenantId: TenantId, artifactId: String): ArtifactRecord? = null
    override fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId?,
        kindFilter: ArtifactKind?,
    ): PageResult<ArtifactRecord> = PageResult(emptyList(), null)
    override fun list(
        tenantId: TenantId,
        filter: ArtifactListFilter,
        page: PageRequest,
    ): PageResult<ArtifactRecord> = PageResult(emptyList(), null)
    override fun deleteExpired(now: Instant): Int = 0
}

internal object EmptyArtifactContentStore : ArtifactContentStore {
    override fun write(
        artifactId: String,
        source: InputStream,
        expectedSizeBytes: Long,
    ): WriteArtifactOutcome = WriteArtifactOutcome.SizeMismatch(expectedSizeBytes, 0)

    override fun openRangeRead(artifactId: String, offset: Long, length: Long): InputStream =
        ByteArrayInputStream(ByteArray(0))

    override fun exists(artifactId: String): Boolean = false

    override fun delete(artifactId: String): Boolean = false
}

internal object EmptySchemaStore : SchemaStore {
    override fun save(entry: SchemaIndexEntry): SchemaIndexEntry = entry
    override fun findById(tenantId: TenantId, schemaId: String): SchemaIndexEntry? = null
    override fun list(tenantId: TenantId, page: PageRequest): PageResult<SchemaIndexEntry> =
        PageResult(emptyList(), null)
    override fun list(
        tenantId: TenantId,
        filter: SchemaListFilter,
        page: PageRequest,
    ): PageResult<SchemaIndexEntry> = PageResult(emptyList(), null)
    override fun deleteExpired(now: Instant): Int = 0
    override fun register(entry: SchemaIndexEntry): dev.dmigrate.server.ports.SchemaRegisterOutcome =
        dev.dmigrate.server.ports.SchemaRegisterOutcome.Registered(entry)
}

internal object EmptyProfileStore : ProfileStore {
    override fun save(entry: ProfileIndexEntry): ProfileIndexEntry = entry
    override fun findById(tenantId: TenantId, profileId: String): ProfileIndexEntry? = null
    override fun list(tenantId: TenantId, page: PageRequest): PageResult<ProfileIndexEntry> =
        PageResult(emptyList(), null)
    override fun list(
        tenantId: TenantId,
        filter: ProfileListFilter,
        page: PageRequest,
    ): PageResult<ProfileIndexEntry> = PageResult(emptyList(), null)
    override fun deleteExpired(now: Instant): Int = 0
}

internal object EmptyDiffStore : DiffStore {
    override fun save(entry: DiffIndexEntry): DiffIndexEntry = entry
    override fun findById(tenantId: TenantId, diffId: String): DiffIndexEntry? = null
    override fun list(tenantId: TenantId, page: PageRequest): PageResult<DiffIndexEntry> =
        PageResult(emptyList(), null)
    override fun list(
        tenantId: TenantId,
        filter: DiffListFilter,
        page: PageRequest,
    ): PageResult<DiffIndexEntry> = PageResult(emptyList(), null)
    override fun deleteExpired(now: Instant): Int = 0
}

internal object EmptyConnectionStore : ConnectionReferenceStore {
    override fun save(reference: ConnectionReference): ConnectionReference = reference
    override fun findById(tenantId: TenantId, connectionId: String): ConnectionReference? = null
    override fun list(
        principal: PrincipalContext,
        page: PageRequest,
    ): PageResult<ConnectionReference> = PageResult(emptyList(), null)
    override fun delete(tenantId: TenantId, connectionId: String): Boolean = false
}
