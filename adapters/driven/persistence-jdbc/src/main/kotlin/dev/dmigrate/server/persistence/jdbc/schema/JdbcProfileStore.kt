package dev.dmigrate.server.persistence.jdbc.schema

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.persistence.jdbc.internal.JdbcTransactionRunner
import dev.dmigrate.server.persistence.jdbc.job.paginate
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.ProfileListFilter
import dev.dmigrate.server.ports.ProfileStore
import java.time.Instant

/** Postgres-/JDBC-Implementierung des [ProfileStore]-Vertrags ueber `profile_index_entries` (Issue #87). */
class JdbcProfileStore(transactionRunner: JdbcTransactionRunner) : ProfileStore {

    private val index = JdbcIndexTable(
        transactionRunner,
        table = "profile_index_entries",
        idColumn = "profile_id",
        decode = ProfileIndexEntryJson::fromJson,
    )

    override fun save(entry: ProfileIndexEntry): ProfileIndexEntry {
        index.upsert(
            IndexRow(
                tenantId = entry.tenantId,
                id = entry.profileId,
                json = ProfileIndexEntryJson.toJson(entry),
                jobRef = entry.jobRef,
                createdAt = entry.createdAt,
                expiresAt = entry.expiresAt,
            ),
        )
        return entry
    }

    override fun findById(tenantId: TenantId, profileId: String): ProfileIndexEntry? = index.find(tenantId, profileId)

    // Wie die uebrigen Stores: ungefiltert aeltestes zuerst, gefiltert neuestes zuerst.
    override fun list(tenantId: TenantId, page: PageRequest): PageResult<ProfileIndexEntry> =
        paginate(index.select(tenantId, IndexQuery(newestFirst = false)), page)

    override fun list(
        tenantId: TenantId,
        filter: ProfileListFilter,
        page: PageRequest,
    ): PageResult<ProfileIndexEntry> {
        val query = IndexQuery(
            jobRef = filter.jobRef,
            createdAfter = filter.createdAfter,
            createdBefore = filter.createdBefore,
        )
        return paginate(index.select(tenantId, query), page)
    }

    override fun deleteExpired(now: Instant): Int = index.deleteExpired(now)
}
