package dev.dmigrate.server.persistence.jdbc.schema

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.job.paginate
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.DiffListFilter
import dev.dmigrate.server.ports.DiffStore
import java.time.Instant

/** Postgres-/JDBC-Implementierung des [DiffStore]-Vertrags ueber `diff_index_entries` (Issue #87). */
class JdbcDiffStore(transactionRunner: JdbcTransactionRunner) : DiffStore {

    private val index = JdbcIndexTable(
        transactionRunner,
        table = "diff_index_entries",
        idColumn = "diff_id",
        decode = DiffIndexEntryJson::fromJson,
    )

    override fun save(entry: DiffIndexEntry): DiffIndexEntry {
        index.upsert(
            IndexRow(
                tenantId = entry.tenantId,
                id = entry.diffId,
                json = DiffIndexEntryJson.toJson(entry),
                jobRef = entry.jobRef,
                createdAt = entry.createdAt,
                expiresAt = entry.expiresAt,
            ),
        )
        return entry
    }

    override fun findById(tenantId: TenantId, diffId: String): DiffIndexEntry? = index.find(tenantId, diffId)

    // Wie die uebrigen Stores: ungefiltert aeltestes zuerst, gefiltert neuestes zuerst.
    override fun list(tenantId: TenantId, page: PageRequest): PageResult<DiffIndexEntry> =
        paginate(index.select(tenantId, IndexQuery(newestFirst = false)), page)

    override fun list(
        tenantId: TenantId,
        filter: DiffListFilter,
        page: PageRequest,
    ): PageResult<DiffIndexEntry> {
        val query = IndexQuery(
            jobRef = filter.jobRef,
            createdAfter = filter.createdAfter,
            createdBefore = filter.createdBefore,
            entryFields = mapOf("sourceRef" to filter.sourceRef, "targetRef" to filter.targetRef),
        )
        return paginate(index.select(tenantId, query), page)
    }

    override fun deleteExpired(now: Instant): Int = index.deleteExpired(now)
}
