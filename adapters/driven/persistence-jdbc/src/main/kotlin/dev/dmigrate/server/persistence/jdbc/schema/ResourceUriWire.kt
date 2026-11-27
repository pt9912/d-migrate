package dev.dmigrate.server.persistence.jdbc.schema

import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri

/** Wire-Form eines [ServerResourceUri] in den Index-Eintraegen: `TenantId` ist ein `value class`-Wrapper. */
internal data class ResourceUriWire(val tenantId: String, val kind: ResourceKind, val id: String) {
    fun toDomain(): ServerResourceUri = ServerResourceUri(TenantId(tenantId), kind, id)

    companion object {
        fun from(u: ServerResourceUri) = ResourceUriWire(u.tenantId.value, u.kind, u.id)
    }
}
