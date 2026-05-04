package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ProfileIndexEntry
import dev.dmigrate.server.ports.ProfileListFilter
import dev.dmigrate.server.ports.ProfileStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryProfileStore : ProfileStore {

    private data class Key(val tenantId: TenantId, val profileId: String)

    private val entries = ConcurrentHashMap<Key, ProfileIndexEntry>()

    override fun save(entry: ProfileIndexEntry): ProfileIndexEntry {
        entries[Key(entry.tenantId, entry.profileId)] = entry
        return entry
    }

    override fun findById(tenantId: TenantId, profileId: String): ProfileIndexEntry? =
        entries[Key(tenantId, profileId)]

    override fun list(tenantId: TenantId, page: PageRequest): PageResult<ProfileIndexEntry> {
        val matching = entries.values
            .filter { it.tenantId == tenantId }
            .sortedBy { it.createdAt }
        return paginate(matching, page)
    }

    override fun list(
        tenantId: TenantId,
        filter: ProfileListFilter,
        page: PageRequest,
    ): PageResult<ProfileIndexEntry> {
        val matching = entries.values
            .filter { it.tenantId == tenantId }
            .filter { filter.jobRef == null || it.jobRef == filter.jobRef }
            .filter { filter.createdAfter == null || !it.createdAt.isBefore(filter.createdAfter) }
            .filter { filter.createdBefore == null || !it.createdAt.isAfter(filter.createdBefore) }
            .sortedWith(
                compareByDescending<ProfileIndexEntry> { it.createdAt }
                    .thenBy { it.profileId },
            )
        return paginate(matching, page)
    }

    override fun deleteExpired(now: Instant): Int {
        val expired = entries.entries
            .filter { it.value.expiresAt.isBefore(now) }
            .map { it.key }
        expired.forEach { entries.remove(it) }
        return expired.size
    }
}
