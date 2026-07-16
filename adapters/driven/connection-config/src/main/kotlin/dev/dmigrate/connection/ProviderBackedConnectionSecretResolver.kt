package dev.dmigrate.connection

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.ports.ConnectionSecretResolver
import dev.dmigrate.server.ports.CredentialProviderRegistry
import dev.dmigrate.server.ports.CredentialResolution
import dev.dmigrate.server.ports.ResolvedConnection

/**
 * MCP-seitiger [ConnectionSecretResolver] (ADR 0035): behandelt die MCP-spezifischen Belange
 * (Principal-Autorisierung gegen `allowedPrincipalIds`/`allowedScopes` + null-`credentialRef`) und
 * delegiert die eigentliche Scheme→URL-Auflösung an die geteilte, **principal-freie**
 * [CredentialProviderRegistry].
 *
 * Ersetzt den früheren flachen `EnvConnectionSecretResolver`; das Verhalten für `env:` bleibt
 * unverändert (Authz zuerst, dann null-Ref, dann Provider-Auflösung; unbekanntes Scheme →
 * `PROVIDER_MISSING`, fail-closed). [CredentialResolution]-`reason`-Codes sind identisch mit den
 * [ResolvedConnection]-Codes und werden verbatim durchgereicht.
 */
class ProviderBackedConnectionSecretResolver(
    private val registry: CredentialProviderRegistry,
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
        return when (val outcome = registry.resolve(ref)) {
            is CredentialResolution.Success -> ResolvedConnection.Success(url = outcome.url)
            is CredentialResolution.Failure -> ResolvedConnection.Failure(outcome.reason, outcome.detail)
        }
    }

    private fun isPrincipalAuthorised(
        reference: ConnectionReference,
        principal: PrincipalContext,
    ): Boolean {
        // No allowlists configured → connection is open to every principal in the tenant scope
        // (the runner layer decides whether the higher-level scope mapping permits the call).
        val allowedIds = reference.allowedPrincipalIds
        val allowedScopes = reference.allowedScopes
        if (allowedIds.isNullOrEmpty() && allowedScopes.isNullOrEmpty()) return true
        if (!allowedIds.isNullOrEmpty() && principal.principalId in allowedIds) return true
        if (!allowedScopes.isNullOrEmpty() && allowedScopes.any { it in principal.scopes }) return true
        if (principal.isAdmin) return true
        return false
    }
}
