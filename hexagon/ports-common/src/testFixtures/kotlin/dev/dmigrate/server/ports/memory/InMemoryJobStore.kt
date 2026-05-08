package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.JobListFilter
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.JobTransitionOutcome
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

    override fun transitionStatus(
        tenantId: TenantId,
        jobId: String,
        allowedFromStatuses: Set<JobStatus>,
        transformer: (ManagedJob) -> ManagedJob,
    ): JobTransitionOutcome {
        val key = Key(tenantId, jobId)
        var outcome: JobTransitionOutcome = JobTransitionOutcome.NotFound
        records.compute(key) { _, current ->
            if (current == null) {
                outcome = JobTransitionOutcome.NotFound
                null
            } else if (current.managedJob.status !in allowedFromStatuses) {
                outcome = JobTransitionOutcome.IllegalTransition(current.managedJob.status)
                current
            } else {
                val next = current.copy(managedJob = transformer(current.managedJob))
                outcome = JobTransitionOutcome.Applied(next)
                next
            }
        }
        return outcome
    }

    override fun markCancelRequested(
        tenantId: TenantId,
        jobId: String,
        requestedAt: Instant,
        requestedBy: String,
        signalSource: String,
        reason: String?,
    ): JobTransitionOutcome {
        val key = Key(tenantId, jobId)
        var outcome: JobTransitionOutcome = JobTransitionOutcome.NotFound
        records.compute(key) { _, current ->
            if (current == null) {
                outcome = JobTransitionOutcome.NotFound
                null
            } else if (current.managedJob.status.terminal) {
                outcome = JobTransitionOutcome.IllegalTransition(current.managedJob.status)
                current
            } else if (current.managedJob.cancelRequest.requested) {
                // Idempotent retry: keep first reason + first metadata,
                // do not overwrite (LF-012 / LN-011 / LN-017 / LN-027).
                outcome = JobTransitionOutcome.Applied(current)
                current
            } else {
                val updated = current.managedJob.copy(
                    updatedAt = requestedAt,
                    cancelRequest = current.managedJob.cancelRequest.copy(
                        requested = true,
                        requestedAt = requestedAt,
                        requestedBy = requestedBy,
                        requestedReason = reason,
                        signalSource = signalSource,
                    ),
                )
                val next = current.copy(managedJob = updated)
                outcome = JobTransitionOutcome.Applied(next)
                next
            }
        }
        return outcome
    }
}
