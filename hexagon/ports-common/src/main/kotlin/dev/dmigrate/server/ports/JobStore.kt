package dev.dmigrate.server.ports

import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

interface JobStore {

    fun save(record: JobRecord): JobRecord

    fun findById(tenantId: TenantId, jobId: String): JobRecord?

    fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId? = null,
    ): PageResult<JobRecord>

    fun deleteExpired(now: Instant): Int
}
