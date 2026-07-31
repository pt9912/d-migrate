package dev.dmigrate.server.ports.memory

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ConnectionReferenceStore
import java.util.concurrent.ConcurrentHashMap

class InMemoryConnectionReferenceStore : ConnectionReferenceStore {

    private data class Key(val tenantId: TenantId, val connectionId: String)

    private val references = ConcurrentHashMap<Key, ConnectionReference>()

    override fun save(reference: ConnectionReference): ConnectionReference {
        references[Key(reference.tenantId, reference.connectionId)] = reference
        return reference
    }

    override fun findById(tenantId: TenantId, connectionId: String): ConnectionReference? =
        references[Key(tenantId, connectionId)]

    override fun list(
        principal: PrincipalContext,
        page: PageRequest,
    ): PageResult<ConnectionReference> {
        val tenant = principal.effectiveTenantId
        val matching = references.values
            .filter { it.tenantId == tenant }
            .filter { ref ->
                val allowed = ref.allowedPrincipalIds
                allowed == null || principal.principalId in allowed
            }
            .sortedBy { it.connectionId }
        return paginate(matching, page)
    }

    override fun delete(tenantId: TenantId, connectionId: String): Boolean =
        references.remove(Key(tenantId, connectionId)) != null
}
