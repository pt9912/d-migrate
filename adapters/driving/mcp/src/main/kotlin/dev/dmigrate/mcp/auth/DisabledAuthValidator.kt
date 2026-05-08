package dev.dmigrate.mcp.auth

import dev.dmigrate.server.core.principal.AuthSource
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.PrincipalId
import dev.dmigrate.server.core.principal.TenantId
import java.time.Instant

/**
 * No-op [AuthValidator] for `AuthMode.DISABLED` per
 * `ImpPlan-0.9.6-B.md` §12.14.
 *
 * Returns a synthetic anonymous-admin [PrincipalContext] regardless
 * of the supplied token. Configuration validation (§12.12) blocks
 * this mode unless the bind address is loopback and there is no
 * public base URL — the fail-closed validators catch any escape.
 *
 * The injected [principal] defaults to [ANONYMOUS_PRINCIPAL] for
 * production parity. Integration tests override this so each
 * transport-run gets its own principal (AP 6.24 §6.24 demands
 * "eigene Tenant/Principal je Transportlauf"); production code
 * never constructs the override path.
 */
class DisabledAuthValidator(
    val principal: PrincipalContext = ANONYMOUS_PRINCIPAL,
) : AuthValidator {

    override suspend fun validate(token: String): BearerValidationResult {
        return BearerValidationResult.Valid(principal)
    }

    /**
     * Default anonymous PrincipalContext for production. The route
     * avoids calling [AuthValidator.validate] entirely on the
     * DISABLED short-circuit; instead it reads the validator's
     * [principal] field directly (which falls back to this default
     * when no override is supplied).
     */
    companion object {
        val ANONYMOUS_PRINCIPAL: PrincipalContext = PrincipalContext(
            principalId = PrincipalId("anonymous"),
            homeTenantId = TenantId("default"),
            effectiveTenantId = TenantId("default"),
            allowedTenantIds = setOf(TenantId("default")),
            scopes = setOf("dmigrate:admin"),
            isAdmin = true,
            auditSubject = "anonymous",
            authSource = AuthSource.ANONYMOUS,
            expiresAt = Instant.MAX,
        )
    }
}
