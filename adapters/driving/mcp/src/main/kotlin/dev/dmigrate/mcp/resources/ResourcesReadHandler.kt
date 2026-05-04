package dev.dmigrate.mcp.resources

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.protocol.ReadResourceResult
import dev.dmigrate.mcp.protocol.ResourceContents
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

/**
 * Handles `resources/read` per `ImpPlan-0.9.6-B.md` §5.5 + §5.6 + §6.9.
 *
 * Error mapping (§12.8: protocol-method errors are JSON-RPC errors,
 * never tool-result envelopes):
 *
 * - Missing or malformed URI → `-32602 InvalidParams`. The reason
 *   from [ServerResourceUri.parse] is included verbatim because it
 *   only describes URI shape, never resource existence.
 * - URI tenant outside [PrincipalContext.allowedTenantIds] →
 *   `-32600 InvalidRequest` with a "tenant scope denied" message.
 *   No record-level lookup runs in this branch, so the response
 *   never confirms whether the requested resource exists.
 * - Within-tenant: lookup + per-record visibility (`isReadableBy`
 *   for jobs/artifacts; tenant scoping is enough for the index-only
 *   stores). Unknown ids, expired records, records the principal
 *   cannot see, and the non-listable [ResourceKind.UPLOAD_SESSIONS]
 *   kind all collapse uniformly to `-32002 Resource not found`.
 *   The error message NEVER mentions the requested URI or whether
 *   the resource exists (§5.6 no-oracle).
 *
 * Successful responses carry a single [ResourceContents] slice with
 * `mimeType=application/json`. The body is produced by
 * [ResourceContentProjector] from the same record types
 * `resources/list` walks, so the secrets-free guarantees match across
 * the two methods.
 */
internal class ResourcesReadHandler(private val stores: ResourceStores) {

    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    fun read(principal: PrincipalContext, rawUri: String): ReadResourceResult {
        val uri = parse(rawUri)
        ensureTenantInScope(principal, uri)
        val payload = lookupContent(principal, uri) ?: throw notFound()
        return ReadResourceResult(
            contents = listOf(
                ResourceContents(
                    uri = uri.render(),
                    mimeType = JSON_MIME,
                    text = gson.toJson(payload),
                ),
            ),
        )
    }

    private fun parse(rawUri: String): ServerResourceUri =
        when (val parsed = ServerResourceUri.parse(rawUri)) {
            is ResourceUriParseResult.Valid -> parsed.uri
            is ResourceUriParseResult.Invalid -> throw ResponseErrorException(
                ResponseError(
                    ResponseErrorCode.InvalidParams,
                    "invalid resource URI: ${parsed.reason}",
                    null,
                ),
            )
        }

    private fun ensureTenantInScope(principal: PrincipalContext, uri: ServerResourceUri) {
        if (uri.tenantId in principal.allowedTenantIds) return
        throw ResponseErrorException(
            ResponseError(
                ResponseErrorCode.InvalidRequest,
                "tenant scope denied for requested resource",
                null,
            ),
        )
    }

    private fun lookupContent(
        principal: PrincipalContext,
        uri: ServerResourceUri,
    ): Map<String, Any?>? = when (uri.kind) {
        ResourceKind.JOBS -> stores.jobStore.findById(uri.tenantId, uri.id)
            ?.takeIf { it.isReadableBy(principal) }
            ?.let(ResourceContentProjector::projectContent)

        ResourceKind.ARTIFACTS -> stores.artifactStore.findById(uri.tenantId, uri.id)
            ?.takeIf { it.isReadableBy(principal) }
            ?.let(ResourceContentProjector::projectContent)

        ResourceKind.SCHEMAS -> stores.schemaStore.findById(uri.tenantId, uri.id)
            ?.let(ResourceContentProjector::projectContent)

        ResourceKind.PROFILES -> stores.profileStore.findById(uri.tenantId, uri.id)
            ?.let(ResourceContentProjector::projectContent)

        ResourceKind.DIFFS -> stores.diffStore.findById(uri.tenantId, uri.id)
            ?.let(ResourceContentProjector::projectContent)

        ResourceKind.CONNECTIONS -> stores.connectionStore.findById(uri.tenantId, uri.id)
            ?.let(ResourceContentProjector::projectContent)

        // Upload sessions are not MCP-readable (they are write-only
        // session state, not resource projections). Collapse into the
        // no-oracle not-found branch so an attacker can't probe the
        // upload-session id space through resources/read.
        ResourceKind.UPLOAD_SESSIONS -> null
    }

    private fun notFound(): ResponseErrorException = ResponseErrorException(
        ResponseError(MCP_RESOURCE_NOT_FOUND_CODE, "Resource not found", null),
    )

    companion object {
        const val JSON_MIME: String = "application/json"

        /**
         * MCP custom server-error code for `resources/read`-not-found
         * per the 2025-11-25 specification. Inside JSON-RPC's
         * server-error band (`-32099..-32000`) so it never collides
         * with lsp4j's reserved [ResponseErrorCode] values.
         */
        const val MCP_RESOURCE_NOT_FOUND_CODE: Int = -32002
    }
}
