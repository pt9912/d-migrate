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
 */
internal class DisabledAuthValidator : AuthValidator {

    override suspend fun validate(token: String): BearerValidationResult {
        return BearerValidationResult.Valid(ANONYMOUS_PRINCIPAL)
    }

    /**
     * Builds the anonymous PrincipalContext used both when no token is
     * supplied AND when a token IS supplied (the disabled validator
     * doesn't inspect tokens). The route avoids calling
     * [AuthValidator.validate] entirely in DISABLED mode but exposes
     * the principal via this helper so the session can be initialised.
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
