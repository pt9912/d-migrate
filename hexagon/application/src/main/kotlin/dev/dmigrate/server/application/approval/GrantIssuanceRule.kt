package dev.dmigrate.server.application.approval

import dev.dmigrate.server.core.approval.GrantRequest
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId

/**
 * Eine Allowlist-Regel des [ConfiguredAllowlistGrantIssuer]. Match-Felder
 * mit `null` = Wildcard. Stellt einen Grant nur aus, wenn alle
 * gesetzten Felder dem [GrantRequest] entsprechen und [grantedScopes]
 * (default: gleich [GrantRequest.requiredScopes]) die geforderten Scopes
 * abdeckt.
 */
data class GrantIssuanceRule(
    val tenantId: TenantId? = null,
    val toolName: String? = null,
    val callerId: PrincipalId? = null,
    /** `null` = exakt die requiredScopes des Requests ausstellen. */
    val grantedScopes: Set<String>? = null,
) {
    fun matches(request: GrantRequest): Boolean {
        if (tenantId != null && tenantId != request.tenantId) return false
        if (toolName != null && toolName != request.toolName) return false
        if (callerId != null && callerId != request.callerId) return false
        return true
    }
}
