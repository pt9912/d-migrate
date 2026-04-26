package dev.dmigrate.server.application.audit

import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

/**
 * Inputs to [AuditScope.around] that are known at scope-start time —
 * usually right after the adapter has parsed the request and assigned
 * a `requestId`. Late-bound fields (payload fingerprint, resource refs)
 * are populated through [AuditFields] from inside the block.
 */
data class AuditContext(
    val requestId: String,
    val toolName: String? = null,
    val tenantId: TenantId? = null,
    val principalId: PrincipalId? = null,
) {
    companion object {

        /**
         * Pulls [TenantId] and [PrincipalId] from a [PrincipalContext].
         * Uses `effectiveTenantId` (the tenant the request is acting on),
         * not `homeTenantId`, so cross-tenant admin actions are recorded
         * under the addressed tenant.
         */
        fun from(
            principal: PrincipalContext,
            requestId: String,
            toolName: String? = null,
        ): AuditContext = AuditContext(
            requestId = requestId,
            toolName = toolName,
            tenantId = principal.effectiveTenantId,
            principalId = principal.principalId,
        )
    }
}
