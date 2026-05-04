package dev.dmigrate.server.ports

import dev.dmigrate.server.core.connection.ConnectionReference
import dev.dmigrate.server.core.principal.PrincipalContext

/**
 * Plan-D §8.1 + §10.10: adapter-neutral port that resolves a
 * secret-free [ConnectionReference] into the concrete JDBC URL /
 * credential needed to actually open a database connection.
 *
 * Discovery surfaces (`resources/list`, `resources/read`, the
 * `*_list` tools) NEVER materialise this resolver — they project
 * the [ConnectionReference] as-is so `credentialRef` /
 * `providerRef` stay opaque pointers. Only the authorised
 * runner / driver execution path (job-start tools, future data-
 * import / data-export pipelines) calls [resolve] with a
 * principal that has been authorised against the connection's
 * `allowedPrincipalIds` / `allowedScopes`.
 *
 * Returning a [ResolvedConnection.Failure] is the fail-closed
 * answer: a missing provider, an unset env var, a principal
 * outside the connection's scope set — all surface as a typed
 * configuration error. Implementations MUST NOT log the resolved
 * URL or secret value at INFO/DEBUG; the resolver's audit
 * surface is the connectionId + sensitivity classification, not
 * the bytes flowing through.
 */
interface ConnectionSecretResolver {

    /**
     * Resolves [reference] to a concrete connection URL for the
     * supplied [principal]. Returns [ResolvedConnection.Success]
     * with the URL the runner should hand to the JDBC driver, or
     * [ResolvedConnection.Failure] with a stable reason code
     * downstream callers map to a fail-closed exit.
     */
    fun resolve(
        reference: ConnectionReference,
        principal: PrincipalContext,
    ): ResolvedConnection
}

sealed interface ResolvedConnection {

    /**
     * The resolver successfully produced [url]. Callers are
     * responsible for not echoing the URL into logs / audit
     * surfaces — the URL carries the secret once expanded.
     */
    data class Success(val url: String) : ResolvedConnection

    /**
     * Resolution failed. [reason] is one of [REASON_PROVIDER_MISSING],
     * [REASON_ENV_NOT_SET], [REASON_PRINCIPAL_NOT_AUTHORISED],
     * [REASON_NO_CREDENTIAL_REF]; [detail] carries an operator-
     * facing message that MUST stay free of resolved secret
     * fragments.
     */
    data class Failure(val reason: String, val detail: String) : ResolvedConnection

    companion object {
        const val REASON_PROVIDER_MISSING: String = "PROVIDER_MISSING"
        const val REASON_ENV_NOT_SET: String = "ENV_NOT_SET"
        const val REASON_PRINCIPAL_NOT_AUTHORISED: String = "PRINCIPAL_NOT_AUTHORISED"
        const val REASON_NO_CREDENTIAL_REF: String = "NO_CREDENTIAL_REF"
    }
}
