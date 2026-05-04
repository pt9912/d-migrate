package dev.dmigrate.mcp.resources

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.protocol.ReadResourceResult
import dev.dmigrate.mcp.protocol.ResourceContents
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.principal.TenantScopeChecker
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
 * - Missing or malformed URI → `-32602 InvalidParams`. The wire
 *   message is the constant `"invalid resource URI"` — concrete
 *   parse reasons stay in the audit log so the error class never
 *   leaks the URI grammar path the caller probed.
 * - URI tenant not equal to the principal's
 *   [PrincipalContext.effectiveTenantId] (matched via
 *   [TenantScopeChecker.isInScope]) → `-32600 InvalidRequest`
 *   with the message `"tenant scope denied for requested resource"`.
 *   No record-level lookup runs in this branch, so the response
 *   never confirms whether the requested resource exists.
 *   §5.6 sanctions this differentiation (`TENANT_SCOPE_DENIED`
 *   distinct from `RESOURCE_NOT_FOUND`) for syntactically-valid
 *   foreign-tenant URIs; the principal already knows their own
 *   `effectiveTenantId`, so the only signal leaked is whether the
 *   *URI grammar* the caller submitted parsed cleanly — the same
 *   signal the InvalidParams branch carries.
 * - Within-tenant: lookup + per-record visibility (`isReadableBy`
 *   for jobs/artifacts; tenant scoping by `effectiveTenantId` is
 *   sufficient for the index-only stores). Unknown ids, expired
 *   records, records the principal cannot see, and the non-listable
 *   [ResourceKind.UPLOAD_SESSIONS] kind all collapse uniformly to
 *   the [MCP_RESOURCE_NOT_FOUND_CODE] no-oracle branch. The error
 *   message NEVER mentions the requested URI or whether the
 *   resource exists.
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
                // The concrete parse reason ("missing scheme prefix",
                // "unknown resource kind: x", ...) would distinguish
                // grammar-failure paths and let a caller probe URI
                // shape without lookup. Keep the wire message
                // constant; reasons belong in the audit log.
                ResponseError(ResponseErrorCode.InvalidParams, INVALID_URI_MESSAGE, null),
            )
        }

    private fun ensureTenantInScope(principal: PrincipalContext, uri: ServerResourceUri) {
        // §5.5 / §5.6: tenant addressing is bound to the principal's
        // *active* tenant. allowedTenantIds is the set the principal
        // may switch INTO via impersonation; reading from a non-active
        // allowed tenant would be a silent cross-tenant read. Use
        // TenantScopeChecker.isInScope so resources/read agrees with
        // resources/list (which scopes every store call by
        // effectiveTenantId).
        if (TenantScopeChecker.isInScope(principal, uri.tenantId)) return
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

        const val INVALID_URI_MESSAGE: String = "invalid resource URI"

        /**
         * MCP custom server-error code for `resources/read`-not-found
         * per the 2025-11-25 specification. Sits in the JSON-RPC
         * server-error band (`-32099..-32000`).
         *
         * Note: lsp4j's [ResponseErrorCode] enum also defines `-32002`
         * — there as `ServerNotInitialized` per the LSP spec. The
         * collision is wire-irrelevant here: this is an MCP server,
         * MCP clients read `-32002` as "Resource not found" per the
         * MCP spec, and the handler emits the raw integer (no enum
         * routing). The unit tests pin the literal value so a future
         * "switch to the enum" cleanup can't silently re-route the
         * meaning to LSP semantics.
         */
        const val MCP_RESOURCE_NOT_FOUND_CODE: Int = -32002
    }
}
