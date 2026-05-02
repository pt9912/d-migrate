package dev.dmigrate.server.core.principal

sealed interface TenantScopeResolution {
    data class Granted(val effectiveTenantId: TenantId) : TenantScopeResolution
    data class Denied(val attemptedTenantId: TenantId) : TenantScopeResolution
}

object TenantScopeChecker {

    fun resolve(principal: PrincipalContext): TenantScopeResolution {
        val allowed = principal.effectiveTenantId == principal.homeTenantId ||
            principal.effectiveTenantId in principal.allowedTenantIds
        return if (allowed) {
            TenantScopeResolution.Granted(principal.effectiveTenantId)
        } else {
            TenantScopeResolution.Denied(principal.effectiveTenantId)
        }
    }

    fun isInScope(principal: PrincipalContext, target: TenantId): Boolean =
        target == principal.effectiveTenantId

    /**
     * Broader reachability check: returns `true` when [target] is the
     * principal's current, home, or any allowed tenant. Used by the
     * MCP schema-source resolver and other no-oracle paths that must
     * deny syntactically out-of-scope tenants before any store is
     * touched. [isInScope] stays the strict variant for places that
     * intentionally treat home/allowed tenants as separate.
     */
    fun isReachable(principal: PrincipalContext, target: TenantId): Boolean =
        target == principal.effectiveTenantId ||
            target == principal.homeTenantId ||
            target in principal.allowedTenantIds
}
