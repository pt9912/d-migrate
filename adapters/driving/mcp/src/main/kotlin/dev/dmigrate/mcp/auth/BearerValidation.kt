package dev.dmigrate.mcp.auth

import dev.dmigrate.server.core.principal.PrincipalContext

/**
 * Bearer-token validation result per `ImpPlan-0.9.6-B.md` §12.14.
 *
 * The validator does NOT decide on Scope sufficiency — that's the
 * caller's job (it knows the JSON-RPC method name and the
 * `McpServerConfig.scopeMapping`). The validator only answers
 * "is this token cryptographically and structurally acceptable?".
 */
sealed interface BearerValidationResult {

    /** Token signature, claims, audience, issuer, algorithm all OK. */
    data class Valid(val principal: PrincipalContext) : BearerValidationResult

    /**
     * Token was supplied but is rejected. [reason] is short, suitable
     * for an `error_description` field — must NOT contain sensitive
     * data (no token bytes, no key material).
     */
    data class Invalid(val reason: String) : BearerValidationResult
}

/**
 * Bearer-token validator interface. Implementations:
 *
 * - `DisabledAuthValidator` — no-op for `AuthMode.DISABLED`
 * - `JwksAuthValidator` — Nimbus JOSE-JWT + `RemoteJWKSet`
 * - `IntrospectionAuthValidator` — RFC 7662 over Ktor-client
 *
 * The validator is invoked once per HTTP request (no per-session
 * caching of `PrincipalContext`); Nimbus' `RemoteJWKSet` provides the
 * key cache.
 */
interface AuthValidator {

    /**
     * Validates [token] against the configured authority. Suspending
     * because Nimbus' JWKS fetch and RFC 7662 introspection both do
     * blocking IO; HTTP handlers must not block their thread.
     */
    suspend fun validate(token: String): BearerValidationResult
}
