package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.JobListFilter
import dev.dmigrate.server.ports.JobStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryJobStore : JobStore {

    private data class Key(val tenantId: TenantId, val jobId: String)

    private val records = ConcurrentHashMap<Key, JobRecord>()

    override fun save(record: JobRecord): JobRecord {
        records[Key(record.tenantId, record.managedJob.jobId)] = record
        return record
    }

    override fun findById(tenantId: TenantId, jobId: String): JobRecord? =
        records[Key(tenantId, jobId)]

    override fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId?,
    ): PageResult<JobRecord> {
        val matching = records.values
            .filter { it.tenantId == tenantId }
            .filter { ownerFilter == null || it.ownerPrincipalId == ownerFilter }
            .sortedBy { it.managedJob.createdAt }
        return paginate(matching, page)
    }

    override fun list(
        tenantId: TenantId,
        filter: JobListFilter,
        page: PageRequest,
    ): PageResult<JobRecord> {
        val matching = records.values
            .filter { it.tenantId == tenantId }
            .filter { filter.ownerFilter == null || it.ownerPrincipalId == filter.ownerFilter }
            .filter { filter.status == null || it.managedJob.status == filter.status }
            .filter { filter.operation == null || it.managedJob.operation == filter.operation }
            .filter { filter.createdAfter == null || !it.managedJob.createdAt.isBefore(filter.createdAfter) }
            .filter { filter.createdBefore == null || !it.managedJob.createdAt.isAfter(filter.createdBefore) }
            // §6.2 default sort: createdAt DESC, jobId ASC.
            .sortedWith(
                compareByDescending<JobRecord> { it.managedJob.createdAt }
                    .thenBy { it.managedJob.jobId },
            )
        return paginate(matching, page)
    }

    override fun deleteExpired(now: Instant): Int {
        val expired = records.entries
            .filter { it.value.managedJob.expiresAt.isBefore(now) }
            .map { it.key }
        expired.forEach { records.remove(it) }
        return expired.size
    }
}
