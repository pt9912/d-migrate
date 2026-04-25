package dev.dmigrate.server.ports

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.pagination.PageRequest
import dev.dmigrate.server.core.pagination.PageResult
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantId

/**
 * Non-secret index for connection references. Bootstrap sources are
 * project/server config, seeds, or a future config provider. The store never
 * returns JDBC URLs with embedded secrets, passwords, or tokens.
 */
interface ConnectionReferenceStore {

    fun save(reference: ConnectionReference): ConnectionReference

    fun findById(tenantId: TenantId, connectionId: String): ConnectionReference?

    fun list(
        principal: PrincipalContext,
        page: PageRequest,
    ): PageResult<ConnectionReference>

    fun delete(tenantId: TenantId, connectionId: String): Boolean
}
