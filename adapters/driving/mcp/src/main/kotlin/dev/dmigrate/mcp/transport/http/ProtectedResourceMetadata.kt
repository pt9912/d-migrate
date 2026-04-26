package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerConfig

/**
 * Builds the Protected-Resource-Metadata document per RFC 9728 and
 * `ImpPlan-0.9.6-B.md` §12.14.
 *
 * Field order is fixed to satisfy the §6.6 golden test:
 * `resource`, `authorization_servers`, `scopes_supported`,
 * `bearer_methods_supported`, optional `x-dmigrate-auth-mode`.
 *
 * Hand-rolled JSON keeps the output deterministic across Jackson /
 * Gson version drifts.
 */
internal object ProtectedResourceMetadata {

    private val SCOPES_SUPPORTED = listOf(
        "dmigrate:read",
        "dmigrate:job:start",
        "dmigrate:artifact:upload",
        "dmigrate:data:write",
        "dmigrate:job:cancel",
        "dmigrate:ai:execute",
        "dmigrate:admin",
    )

    fun render(config: McpServerConfig, resourceUri: String): String {
        val authServers = when (config.authMode) {
            AuthMode.DISABLED -> emptyList()
            AuthMode.JWT_JWKS, AuthMode.JWT_INTROSPECTION ->
                listOfNotNull(config.issuer?.toString())
        }
        val sb = StringBuilder("{")
        sb.append("\"resource\":\"${JsonStringEscaper.escape(resourceUri)}\",")
        sb.append("\"authorization_servers\":[")
        authServers.forEachIndexed { i, server ->
            if (i > 0) sb.append(',')
            sb.append("\"${JsonStringEscaper.escape(server)}\"")
        }
        sb.append("],")
        sb.append("\"scopes_supported\":[")
        SCOPES_SUPPORTED.forEachIndexed { i, scope ->
            if (i > 0) sb.append(',')
            sb.append("\"${JsonStringEscaper.escape(scope)}\"")
        }
        sb.append("],")
        sb.append("\"bearer_methods_supported\":[\"header\"]")
        if (config.authMode == AuthMode.DISABLED) {
            sb.append(",\"x-dmigrate-auth-mode\":\"disabled\"")
        }
        sb.append('}')
        return sb.toString()
    }
}
