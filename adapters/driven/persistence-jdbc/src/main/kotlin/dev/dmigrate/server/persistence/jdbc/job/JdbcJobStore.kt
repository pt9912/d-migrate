package dev.dmigrate.server.persistence.jdbc.job

import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.internal.bindAll
import dev.dmigrate.server.persistence.jdbc.internal.executeUpdate
import dev.dmigrate.server.persistence.jdbc.internal.querySingle
import dev.dmigrate.server.ports.JobListFilter
import dev.dmigrate.server.ports.JobStore
import dev.dmigrate.server.ports.JobTransitionOutcome
import java.sql.Connection
import java.time.Instant

/**
 * Postgres-/JDBC-Implementierung des [JobStore]-Vertrags. SQL-Patterns:
 * Plan § 6.7 in `docs/planning/in-progress/ImpPlan-0.9.6-E2.md`.
 *
 * Atomicity: alle Statusuebergaenge laufen ueber `SELECT … FOR UPDATE`
 * + UPDATE in einer TX (siehe `transitionStatus`/`markCancelRequested`).
 * Save ist UPSERT (`ON CONFLICT DO UPDATE`); Tests rufen save normalerweise
 * nur einmal pro `(tenantId, jobId)`, aber UPSERT haelt
 * Replays/Retries idempotent.
 *
 * Pagination ist offset-basiert mit string-encoded Token, kompatibel
 * zur Bestands-InMemory-Implementation und zu
 * [dev.dmigrate.server.ports.memory.PaginationHelper].
 */
class JdbcJobStore(
    private val transactionRunner: JdbcTransactionRunner,
) : JobStore {

    override fun save(record: JobRecord): JobRecord = transactionRunner.inTransaction { conn ->
        saveOnConnection(conn, record)
    }

    /**
     * Plan E2 § 3.5 + § 6.5 Cross-Store-Komposition: erlaubt
     * [JdbcJobStartTransaction] das `save` und ein `IdempotencyStore.commit`
     * in derselben DB-TX auszufuehren. Caller MUSS im
     * `JdbcTransactionRunner.inTransaction`-Block sein und die
     * Connection durchreichen.
     */
    internal fun saveOnConnection(conn: Connection, record: JobRecord): JobRecord {
        val mj = record.managedJob
        val cancelRequested = mj.cancelRequest.requested
        val cancelSource = mj.cancelRequest.signalSource
        val managedJobJson = JobRecordJson.toJson(record)
        conn.executeUpdate(
            sql = """
                INSERT INTO jobs
                  (tenant_id, job_id, status, managed_job,
                   cancel_requested, cancel_source,
                   created_at, updated_at, expires_at)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, job_id) DO UPDATE SET
                  status = EXCLUDED.status,
                  managed_job = EXCLUDED.managed_job,
                  cancel_requested = EXCLUDED.cancel_requested,
                  cancel_source = EXCLUDED.cancel_source,
                  updated_at = EXCLUDED.updated_at,
                  expires_at = EXCLUDED.expires_at
            """.trimIndent(),
            record.tenantId.value, mj.jobId, mj.status.name, managedJobJson,
            cancelRequested, cancelSource,
            mj.createdAt, mj.updatedAt, mj.expiresAt,
        )
        return record
    }

    override fun findById(tenantId: TenantId, jobId: String): JobRecord? =
        transactionRunner.inTransaction { conn -> conn.findRecord(tenantId, jobId) }

    private fun Connection.findRecord(tenantId: TenantId, jobId: String): JobRecord? = querySingle(
        sql = """
            SELECT managed_job::text AS managed_job_text
              FROM jobs
             WHERE tenant_id = ? AND job_id = ?
        """.trimIndent(),
        tenantId.value, jobId,
    ) { rs -> JobRecordJson.fromJson(rs.getString("managed_job_text")) }

    override fun list(
        tenantId: TenantId,
        page: PageRequest,
        ownerFilter: PrincipalId?,
    ): PageResult<JobRecord> = transactionRunner.inTransaction { conn ->
        val ownerFilterValue = ownerFilter?.value
        val items = conn.fetchSorted(
            tenantId = tenantId,
            ownerFilterValue = ownerFilterValue,
            statusFilter = null,
            operationFilter = null,
            createdAfter = null,
            createdBefore = null,
            sortDescending = false,
        )
        paginate(items, page)
    }

    override fun list(
        tenantId: TenantId,
        filter: JobListFilter,
        page: PageRequest,
    ): PageResult<JobRecord> = transactionRunner.inTransaction { conn ->
        val items = conn.fetchSorted(
            tenantId = tenantId,
            ownerFilterValue = filter.ownerFilter?.value,
            statusFilter = filter.status?.name,
            operationFilter = filter.operation,
            createdAfter = filter.createdAfter,
            createdBefore = filter.createdBefore,
            // Plan §6.3 default sort: createdAt DESC, jobId ASC.
            sortDescending = true,
        )
        paginate(items, page)
    }

    private fun Connection.fetchSorted(
        tenantId: TenantId,
        ownerFilterValue: String?,
        statusFilter: String?,
        operationFilter: String?,
        createdAfter: Instant?,
        createdBefore: Instant?,
        sortDescending: Boolean,
    ): List<JobRecord> {
        val direction = if (sortDescending) "DESC" else "ASC"
        val sql = """
            SELECT managed_job::text AS managed_job_text
              FROM jobs
             WHERE tenant_id = ?
               AND (?::text IS NULL OR managed_job->>'ownerPrincipalId' = ?)
               AND (?::text IS NULL OR status = ?)
               AND (?::text IS NULL OR managed_job->'managedJob'->>'operation' = ?)
               AND (?::timestamptz IS NULL OR created_at >= ?)
               AND (?::timestamptz IS NULL OR created_at <= ?)
             ORDER BY created_at $direction, job_id ASC
        """.trimIndent()
        return prepareStatement(sql).use { ps ->
            ps.bindAll(
                tenantId.value,
                ownerFilterValue, ownerFilterValue,
                statusFilter, statusFilter,
                operationFilter, operationFilter,
                createdAfter, createdAfter,
                createdBefore, createdBefore,
            )
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(JobRecordJson.fromJson(rs.getString("managed_job_text")))
                    }
                }
            }
        }
    }

    private fun paginate(items: List<JobRecord>, page: PageRequest): PageResult<JobRecord> {
        val pageSize = page.pageSize.coerceAtLeast(1)
        val offset = page.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val effectiveOffset = offset.coerceAtMost(items.size)
        val end = (effectiveOffset + pageSize).coerceAtMost(items.size)
        val slice = items.subList(effectiveOffset, end)
        val nextToken = if (end < items.size) end.toString() else null
        return PageResult(items = slice, nextPageToken = nextToken)
    }

    override fun deleteExpired(now: Instant): Int = transactionRunner.inTransaction { conn ->
        conn.executeUpdate("DELETE FROM jobs WHERE expires_at < ?", now)
    }

    override fun transitionStatus(
        tenantId: TenantId,
        jobId: String,
        allowedFromStatuses: Set<JobStatus>,
        transformer: (ManagedJob) -> ManagedJob,
    ): JobTransitionOutcome = transactionRunner.inTransaction { conn ->
        val locked = conn.lockRow(tenantId, jobId)
            ?: return@inTransaction JobTransitionOutcome.NotFound
        if (locked.managedJob.status !in allowedFromStatuses) {
            return@inTransaction JobTransitionOutcome.IllegalTransition(locked.managedJob.status)
        }
        val updatedManaged = transformer(locked.managedJob)
        val next = locked.copy(managedJob = updatedManaged)
        conn.writeUpdate(next)
        JobTransitionOutcome.Applied(next)
    }

    override fun markCancelRequested(
        tenantId: TenantId,
        jobId: String,
        requestedAt: Instant,
        requestedBy: String,
        signalSource: String,
        reason: String?,
    ): JobTransitionOutcome = transactionRunner.inTransaction { conn ->
        val locked = conn.lockRow(tenantId, jobId)
            ?: return@inTransaction JobTransitionOutcome.NotFound
        if (locked.managedJob.status.terminal) {
            return@inTransaction JobTransitionOutcome.IllegalTransition(locked.managedJob.status)
        }
        // Plan § 7.2 Idempotenz: bei bereits requested = TRUE keine
        // Reason-/Source-Ueberschreibung — ersten Wert behalten.
        if (locked.managedJob.cancelRequest.requested) {
            return@inTransaction JobTransitionOutcome.Applied(locked)
        }
        val updatedCancel = locked.managedJob.cancelRequest.copy(
            requested = true,
            requestedAt = requestedAt,
            requestedBy = requestedBy,
            requestedReason = reason,
            signalSource = signalSource,
        )
        val updatedManaged = locked.managedJob.copy(
            updatedAt = requestedAt,
            cancelRequest = updatedCancel,
        )
        val next = locked.copy(managedJob = updatedManaged)
        conn.writeUpdate(next)
        JobTransitionOutcome.Applied(next)
    }

    private fun Connection.lockRow(tenantId: TenantId, jobId: String): JobRecord? = querySingle(
        sql = """
            SELECT managed_job::text AS managed_job_text
              FROM jobs
             WHERE tenant_id = ? AND job_id = ?
             FOR UPDATE
        """.trimIndent(),
        tenantId.value, jobId,
    ) { rs -> JobRecordJson.fromJson(rs.getString("managed_job_text")) }

    private fun Connection.writeUpdate(record: JobRecord) {
        val mj = record.managedJob
        val cancelRequested = mj.cancelRequest.requested
        val cancelSource = mj.cancelRequest.signalSource
        val managedJobJson = JobRecordJson.toJson(record)
        executeUpdate(
            sql = """
                UPDATE jobs SET
                    status = ?,
                    managed_job = ?::jsonb,
                    cancel_requested = ?,
                    cancel_source = ?,
                    updated_at = ?,
                    expires_at = ?
                  WHERE tenant_id = ? AND job_id = ?
            """.trimIndent(),
            mj.status.name, managedJobJson,
            cancelRequested, cancelSource,
            mj.updatedAt, mj.expiresAt,
            record.tenantId.value, mj.jobId,
        )
    }
}
