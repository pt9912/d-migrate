package dev.dmigrate.server.core.job

import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri

enum class JobVisibility { OWNER, TENANT, ADMIN }

data class JobRecord(
    val managedJob: ManagedJob,
    val tenantId: TenantId,
    val ownerPrincipalId: PrincipalId,
    val visibility: JobVisibility,
    val resourceUri: ServerResourceUri,
    val adminScope: String? = null,
) {
    fun isReadableBy(
        principal: PrincipalContext,
        addressedTenantId: TenantId = principal.effectiveTenantId,
    ): Boolean {
        if (tenantId != addressedTenantId) return false
        return when (visibility) {
            JobVisibility.OWNER -> principal.principalId == ownerPrincipalId
            JobVisibility.TENANT -> true
            JobVisibility.ADMIN -> principal.isAdmin
        }
    }
}
