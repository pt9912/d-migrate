package dev.dmigrate.connection

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.ports.ConnectionReferenceConfigLoader
import dev.dmigrate.server.ports.ConnectionReferenceStore
import java.util.concurrent.ConcurrentHashMap

/**
 * Plan-D §10.10 production-grade [ConnectionReferenceStore] that
 * eager-loads from a [ConnectionReferenceConfigLoader] at
 * construction time and serves Phase-D discovery requests from an
 * in-memory map.
 *
 * The loader is consulted exactly ONCE — the discovery surface
 * (`resources/list`, `resources/read`, the `*_list` discovery
 * tools) reads from the cached map. A future refresh contract
 * (file-watching / SIGHUP / control-plane push) can wire in via
 * the additive [save] / [delete] surface; for now the bootstrap
 * loads the YAML once and the runtime stays consistent until
 * server restart.
 *
 * `list()` filters per-record by the principal's
 * `allowedPrincipalIds` allowlist and the tenant scope — same
 * shape the test-fixture `InMemoryConnectionReferenceStore`
 * provides for unit tests, but production-fit (no testFixtures
 * dependency).
 */
class LoaderBackedConnectionReferenceStore(
    loader: ConnectionReferenceConfigLoader,
) : ConnectionReferenceStore {

    private data class Key(val tenantId: TenantId, val connectionId: String)

    private val references = ConcurrentHashMap<Key, ConnectionReference>().apply {
        for (ref in loader.loadAll()) {
            put(Key(ref.tenantId, ref.connectionId), ref)
        }
    }

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
                allowed == null || principal.principalId in allowed || principal.isAdmin
            }
            .sortedBy { it.connectionId }
        // Paging follows the same offset semantics the test-
        // fixture InMemory* stores use; the bootstrap typically
        // loads <100 connections so pagination is rarely exercised.
        val pageSize = page.pageSize.coerceAtLeast(1)
        val offset = page.pageToken?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val effectiveOffset = offset.coerceAtMost(matching.size)
        val end = (effectiveOffset + pageSize).coerceAtMost(matching.size)
        val slice = matching.subList(effectiveOffset, end)
        val nextToken = if (end < matching.size) end.toString() else null
        return PageResult(items = slice, nextPageToken = nextToken)
    }

    override fun delete(tenantId: TenantId, connectionId: String): Boolean =
        references.remove(Key(tenantId, connectionId)) != null
}
