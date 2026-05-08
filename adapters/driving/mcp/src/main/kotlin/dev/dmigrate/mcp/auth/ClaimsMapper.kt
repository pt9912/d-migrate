package dev.dmigrate.mcp.auth

import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * Pure mapping from a parsed JWT / introspection claim set to a
 * [PrincipalContext] per `ImpPlan-0.9.6-B.md` §12.14.
 *
 * The mapping is intentionally side-effect-free and decoupled from
 * Nimbus types so it can be reused by JWKS and Introspection
 * validators (and unit-tested without crypto fixtures).
 */
internal object ClaimsMapper {

    private const val DMIGRATE_ADMIN_SCOPE = "dmigrate:admin"
    private const val DEFAULT_TENANT = "default"

    /**
     * Builds a [PrincipalContext] from the supplied claims.
     *
     * - `subject` -> `principalId` and `auditSubject`. Caller MUST
     *   reject empty subjects before calling this mapper.
     * - tenant id from `tenant_id`, fallback `tid` (Microsoft), then
     *   the [DEFAULT_TENANT] literal.
     * - `effectiveTenantId == homeTenantId` and
     *   `allowedTenantIds == {homeTenantId}` for Phase B (no
     *   cross-tenant impersonation).
     * - `isAdmin` derived from membership of `dmigrate:admin` in the
     *   resolved scopes — never trust an `is_admin` claim directly.
     */
    fun map(
        subject: String,
        tenantClaim: String?,
        tidClaim: String?,
        scopes: Set<String>,
        expiresAt: Instant,
        authSource: AuthSource = AuthSource.OIDC,
    ): PrincipalContext {
        val tenant = tenantClaim?.takeUnless { it.isBlank() }
            ?: tidClaim?.takeUnless { it.isBlank() }
            ?: DEFAULT_TENANT
        val tenantId = TenantId(tenant)
        return PrincipalContext(
            principalId = PrincipalId(subject),
            homeTenantId = tenantId,
            effectiveTenantId = tenantId,
            allowedTenantIds = setOf(tenantId),
            scopes = scopes,
            isAdmin = DMIGRATE_ADMIN_SCOPE in scopes,
            auditSubject = subject,
            authSource = authSource,
            expiresAt = expiresAt,
        )
    }

    /**
     * Resolves scopes from claim shapes per RFC 8693 §4.2 (`scope` as
     * space-separated string) preferred over Microsoft's `scp` array.
     * Empty string and empty array both map to an empty scope set.
     */
    fun parseScopes(scopeClaim: String?, scpClaim: List<String>?): Set<String> {
        if (scopeClaim != null) {
            return scopeClaim.split(' ').filter { it.isNotBlank() }.toSet()
        }
        if (scpClaim != null) {
            return scpClaim.filter { it.isNotBlank() }.toSet()
        }
        return emptySet()
    }
}
