package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaStore
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

class InMemorySchemaStore : SchemaStore {

    private data class Key(val tenantId: TenantId, val schemaId: String)

    private val entries = ConcurrentHashMap<Key, SchemaIndexEntry>()

    override fun save(entry: SchemaIndexEntry): SchemaIndexEntry {
        entries[Key(entry.tenantId, entry.schemaId)] = entry
        return entry
    }

    override fun findById(tenantId: TenantId, schemaId: String): SchemaIndexEntry? =
        entries[Key(tenantId, schemaId)]

    override fun list(tenantId: TenantId, page: PageRequest): PageResult<SchemaIndexEntry> {
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
