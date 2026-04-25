package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
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

    override fun deleteExpired(now: Instant): Int {
        val expired = records.entries
            .filter { it.value.managedJob.expiresAt.isBefore(now) }
            .map { it.key }
        expired.forEach { records.remove(it) }
        return expired.size
    }
}
