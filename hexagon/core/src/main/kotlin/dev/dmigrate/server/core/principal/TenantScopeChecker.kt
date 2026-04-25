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
}
