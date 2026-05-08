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
    /**
     * Plan-D §6.4 mindestfeld for `schema_list`: serialised
     * format hint (`json`, `yaml`, ...). Null means "not yet
     * recorded by the producer" — Phase-E start tools fill it
     * when staging.
     */
    val format: String? = null,
    /**
     * Plan-D §6.4: lineage tag — typically `schema_generate`,
     * `schema_reverse_start` (Phase E), `upload`, or another
     * canonical operation id the producer used.
     */
    val origin: String? = null,
    /**
     * Plan-D §6.4: serialised payload byte length. Null when
     * the producer did not capture it.
     */
    val sizeBytes: Long? = null,
    /**
     * Plan-D §6.4 optional hash hint (typically the sha256 of
     * the underlying artefact bytes, hex-lowercase).
     */
    val hash: String? = null,
)

/**
 * Phase-D §6.3 + §10.4 filter for `schema_list`. `jobRef` matches
 * `SchemaIndexEntry.jobRef`; time window inclusive at both ends.
 */
data class SchemaListFilter(
    val jobRef: String? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
)

interface SchemaStore {

    fun save(entry: SchemaIndexEntry): SchemaIndexEntry

    fun findById(tenantId: TenantId, schemaId: String): SchemaIndexEntry?

    fun list(tenantId: TenantId, page: PageRequest): PageResult<SchemaIndexEntry>

    /**
     * Phase-D filtered list. Default sort:
     *   1. `createdAt` DESC
     *   2. `schemaId` ASC (stable id tiebreaker)
     */
    fun list(
        tenantId: TenantId,
        filter: SchemaListFilter,
        page: PageRequest,
    ): PageResult<SchemaIndexEntry>

    fun deleteExpired(now: Instant): Int

    /**
     * AP 6.22: idempotent registration for the deterministic
     * [SchemaIndexEntry.schemaId] derived from tenant +
     * uploadSessionId + payload SHA + format. Same id with the
     * same `(tenantId, artifactRef)` is a no-op that returns the
     * existing entry — used by replays of a successful finalisation
     * to recover the same `schemaRef` without producing a duplicate.
     * Same id with diverging `tenantId` or `artifactRef` is a hard
     * internal inconsistency and reported via
     * [SchemaRegisterOutcome.Conflict].
     */
    fun register(entry: SchemaIndexEntry): SchemaRegisterOutcome
}

sealed interface SchemaRegisterOutcome {
    data class Registered(val entry: SchemaIndexEntry) : SchemaRegisterOutcome
    data class AlreadyRegistered(val existing: SchemaIndexEntry) : SchemaRegisterOutcome
    data class Conflict(
        val existing: SchemaIndexEntry,
        val attempted: SchemaIndexEntry,
    ) : SchemaRegisterOutcome
}
