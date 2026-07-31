package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.SchemaIndexEntry
import dev.dmigrate.server.ports.SchemaListFilter
import dev.dmigrate.server.ports.SchemaRegisterOutcome
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

    override fun list(
        tenantId: TenantId,
        filter: SchemaListFilter,
        page: PageRequest,
    ): PageResult<SchemaIndexEntry> {
        val matching = entries.values
            .filter { it.tenantId == tenantId }
            .filter { filter.jobRef == null || it.jobRef == filter.jobRef }
            .filter { filter.createdAfter == null || !it.createdAt.isBefore(filter.createdAfter) }
            .filter { filter.createdBefore == null || !it.createdAt.isAfter(filter.createdBefore) }
            .sortedWith(
                compareByDescending<SchemaIndexEntry> { it.createdAt }
                    .thenBy { it.schemaId },
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

    override fun register(entry: SchemaIndexEntry): SchemaRegisterOutcome {
        val key = Key(entry.tenantId, entry.schemaId)
        var outcome: SchemaRegisterOutcome? = null
        entries.compute(key) { _, existing ->
            if (existing == null) {
                outcome = SchemaRegisterOutcome.Registered(entry)
                entry
            } else if (existing.tenantId == entry.tenantId && existing.artifactRef == entry.artifactRef) {
                // Idempotent re-registration: same artefact behind the
                // same deterministic schemaId. Return the persisted
                // entry so callers reuse its createdAt / labels.
                outcome = SchemaRegisterOutcome.AlreadyRegistered(existing)
                existing
            } else {
                outcome = SchemaRegisterOutcome.Conflict(existing, entry)
                existing
            }
        }
        return outcome ?: SchemaRegisterOutcome.Registered(entry)
    }
}
