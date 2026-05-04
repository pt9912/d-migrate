package dev.dmigrate.server.core.connection

import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ServerResourceUri

enum class ConnectionSensitivity { NON_PRODUCTION, PRODUCTION, SENSITIVE }

data class ConnectionReference(
    val connectionId: String,
    val tenantId: TenantId,
    val displayName: String,
    val dialectId: String,
    val sensitivity: ConnectionSensitivity,
    val resourceUri: ServerResourceUri,
    val credentialRef: String? = null,
    val providerRef: String? = null,
    val allowedPrincipalIds: Set<PrincipalId>? = null,
    val allowedScopes: Set<String>? = null,
) {
    /**
     * Plan-D §6.4 + §10.10 visibility check. Mirrors the
     * `LoaderBackedConnectionReferenceStore.list()` allowlist
     * filter so that `resources/read` and `resources/list` agree
     * on whether a principal may see a connection ref:
     *
     * - Tenant must match the addressed tenant
     *   (Plan-D §4.2 broadens this from `effectiveTenantId` to
     *   `allowedTenantIds`; the caller passes the addressed
     *   tenant as the override)
     * - When [allowedPrincipalIds] is set: the principal MUST be
     *   in the allowlist (or be admin)
     * - When [allowedScopes] is set: the principal MUST hold at
     *   least one of the listed scopes (or be admin)
     * - When both allowlists are empty: the connection is
     *   tenant-visible to every principal in the tenant scope
     *
     * The check intentionally does NOT consult `sensitivity` —
     * sensitivity is metadata, not an access gate.
     */
    fun isReadableBy(
        principal: PrincipalContext,
        addressedTenantId: TenantId = principal.effectiveTenantId,
    ): Boolean {
        if (tenantId != addressedTenantId) return false
        if (principal.isAdmin) return true
        val ids = allowedPrincipalIds
        val scopes = allowedScopes
        if (ids.isNullOrEmpty() && scopes.isNullOrEmpty()) return true
        if (!ids.isNullOrEmpty() && principal.principalId in ids) return true
        if (!scopes.isNullOrEmpty() && scopes.any { it in principal.scopes }) return true
        return false
    }
}
