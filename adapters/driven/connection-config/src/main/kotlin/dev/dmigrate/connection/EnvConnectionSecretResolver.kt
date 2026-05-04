package dev.dmigrate.connection

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.ResolvedConnection

/**
 * Plan-D §8.1 default [ConnectionSecretResolver] that expands the
 * `env:VAR_NAME` provider scheme by reading from the process
 * environment.
 *
 * The resolver expects [ConnectionReference.credentialRef] in the
 * shape `"env:<VAR_NAME>"`. Anything else surfaces as
 * [ResolvedConnection.Failure] with [ResolvedConnection.REASON_PROVIDER_MISSING]
 * — Plan-D's fail-closed contract: a connection record without
 * a wired secret provider MUST NOT silently degrade to a no-secret
 * connection.
 *
 * Authorisation: when [ConnectionReference.allowedPrincipalIds] or
 * [ConnectionReference.allowedScopes] is set, the resolver checks
 * the calling [principal] against either set. A principal outside
 * both sets surfaces as [ResolvedConnection.REASON_PRINCIPAL_NOT_AUTHORISED]
 * — the runner / driver path SHALL refuse to open the connection.
 *
 * The resolver does NOT log the resolved URL or expanded env value;
 * audit surfaces stay limited to `connectionId` + `sensitivity`.
 */
class EnvConnectionSecretResolver(
    private val envLookup: (String) -> String? = System::getenv,
    private val urlFromEnv: (envVarValue: String) -> String = { it },
) : ConnectionSecretResolver {

    override fun resolve(
        reference: ConnectionReference,
        principal: PrincipalContext,
    ): ResolvedConnection {
        if (!isPrincipalAuthorised(reference, principal)) {
            return ResolvedConnection.Failure(
                reason = ResolvedConnection.REASON_PRINCIPAL_NOT_AUTHORISED,
                detail = "principal '${principal.principalId.value}' not authorised " +
                    "for connection '${reference.connectionId}'",
            )
        }
        val ref = reference.credentialRef
            ?: return ResolvedConnection.Failure(
                reason = ResolvedConnection.REASON_NO_CREDENTIAL_REF,
                detail = "connection '${reference.connectionId}' carries no credentialRef",
            )
        if (!ref.startsWith(ENV_SCHEME)) {
            return ResolvedConnection.Failure(
                reason = ResolvedConnection.REASON_PROVIDER_MISSING,
                detail = "credentialRef '$ref' uses an unsupported provider scheme; " +
                    "this resolver only supports '$ENV_SCHEME...' references",
            )
        }
        val envName = ref.removePrefix(ENV_SCHEME)
        val envValue = envLookup(envName)
            ?: return ResolvedConnection.Failure(
                reason = ResolvedConnection.REASON_ENV_NOT_SET,
                detail = "environment variable '$envName' (referenced by connection " +
                    "'${reference.connectionId}') is not set",
            )
        return ResolvedConnection.Success(url = urlFromEnv(envValue))
    }

    private fun isPrincipalAuthorised(
        reference: ConnectionReference,
        principal: PrincipalContext,
    ): Boolean {
        // No allowlists configured → connection is open to every
        // principal in the tenant scope (the runner-layer decides
        // whether the higher-level scope mapping permits the call).
        val allowedIds = reference.allowedPrincipalIds
        val allowedScopes = reference.allowedScopes
        if (allowedIds.isNullOrEmpty() && allowedScopes.isNullOrEmpty()) return true
        if (!allowedIds.isNullOrEmpty() && principal.principalId in allowedIds) return true
        if (!allowedScopes.isNullOrEmpty() && allowedScopes.any { it in principal.scopes }) return true
        if (principal.isAdmin) return true
        return false
    }

    companion object {
        const val ENV_SCHEME: String = "env:"
    }
}
