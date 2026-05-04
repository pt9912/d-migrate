package dev.dmigrate.server.ports

import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * Phase-D §6.3 + §10.4 filter for `job_list`. Every field is
 * optional; an empty filter selects every job in the tenant.
 *
 * Time window: `createdAfter` is INCLUSIVE, `createdBefore` is
 * INCLUSIVE on the upper bound — the §10.4 store-contract test
 * pins the boundary case.
 */
data class JobListFilter(
    val ownerFilter: PrincipalId? = null,
    val status: JobStatus? = null,
    val operation: String? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
)

interface JobStore {

    fun save(record: JobRecord): JobRecord

    fun findById(tenantId: TenantId, jobId: String): JobRecord?

    fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId? = null,
    ): PageResult<JobRecord>

    /**
     * Phase-D filtered list. Default sort:
     *   1. `managedJob.createdAt` DESC
     *   2. `managedJob.jobId` ASC (stable id tiebreaker)
     */
    fun list(
        tenantId: TenantId,
        filter: JobListFilter,
        page: PageRequest,
    ): PageResult<JobRecord>

    fun deleteExpired(now: Instant): Int
}
