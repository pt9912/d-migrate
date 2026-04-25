package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.DiffIndexEntry
import dev.dmigrate.server.ports.DiffStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemoryDiffStore : DiffStore {

    private data class Key(val tenantId: TenantId, val diffId: String)

    private val entries = ConcurrentHashMap<Key, DiffIndexEntry>()

    override fun save(entry: DiffIndexEntry): DiffIndexEntry {
        entries[Key(entry.tenantId, entry.diffId)] = entry
        return entry
    }

    override fun findById(tenantId: TenantId, diffId: String): DiffIndexEntry? =
        entries[Key(tenantId, diffId)]

    override fun list(tenantId: TenantId, page: PageRequest): PageResult<DiffIndexEntry> {
        val matching = entries.values
            .filter { it.tenantId == tenantId }
            .sortedBy { it.createdAt }
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
