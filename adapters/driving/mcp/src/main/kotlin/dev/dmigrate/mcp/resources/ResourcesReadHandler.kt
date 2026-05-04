package dev.dmigrate.mcp.resources

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.protocol.ReadResourceResult
import dev.dmigrate.mcp.protocol.ResourceContents
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ArtifactChunkResourceUri
import dev.dmigrate.server.core.resource.GlobalCapabilitiesResourceUri
import dev.dmigrate.server.core.resource.McpResourceError
import dev.dmigrate.server.core.resource.McpResourceUri
import dev.dmigrate.server.core.resource.ResourceClassification
import dev.dmigrate.server.core.resource.ResourceErrorPrecedence
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.TenantResourceUri
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode

/**
 * Handles `resources/read` per `ImpPlan-0.9.6-D.md` §4.2 + §5.3 + §10.7.
 *
 * Phase-D upgrade over the Phase-B handler:
 *
 * - Goes through [ResourceErrorPrecedence] so URI-grammar / tenant-
 *   scope / blocked-kind precedence is centralised and shared with
 *   the discovery list-tools and the chunk/status resolvers landed
 *   later in Phase D.
 * - Tenant-scope is `allowedTenantIds` (Plan-D §4.2 / §5.4) — a
 *   principal whose home tenant differs from the URI tenant may
 *   still read records there as long as the URI tenant is in the
 *   allowed set. Phase-B used `effectiveTenantId` (strict equality);
 *   Plan-D explicitly broadens the contract.
 * - Every JSON-RPC error envelope carries
 *   `error.data.dmigrateCode` (`VALIDATION_ERROR`,
 *   `TENANT_SCOPE_DENIED`, `RESOURCE_NOT_FOUND`) so clients can
 *   branch on the stable d-migrate code without parsing the
 *   numeric JSON-RPC code. Phase-B left `error.data` null.
 * - `dmigrate://capabilities` is a first-class readable resource
 *   per Plan-D §5.1 / §10.7. The capabilities body is supplied by
 *   the bootstrap so this handler stays free of registry/limits
 *   plumbing.
 * - `UPLOAD_SESSIONS` URIs in an *allowed* tenant collapse to
 *   `VALIDATION_ERROR` (Plan-D §5.1: kind not readable in Phase D)
 *   instead of the Phase-B `RESOURCE_NOT_FOUND` no-oracle. The
 *   precedence chain runs the blocked-kind gate before any store
 *   lookup so an attacker cannot probe upload-session ids through
 *   the not-found timing channel.
 *
 * Wire details that stay identical to Phase-B:
 *
 * - Successful responses carry a single [ResourceContents] slice
 *   with `mimeType=application/json`. The body comes from
 *   [ResourceContentProjector] and is identical for jobs / artefacts
 *   / schemas / profiles / diffs / connections.
 * - Within-tenant lookups still apply per-record visibility
 *   ([JobRecord.isReadableBy] / [ArtifactRecord.isReadableBy]) —
 *   the addressed tenant from the URI is threaded into the check
 *   so an explicit allowed-but-non-effective tenant URI doesn't
 *   silently drop every record.
 * - Unknown ids, expired records and records the principal cannot
 *   see all collapse to [MCP_RESOURCE_NOT_FOUND_CODE] with
 *   `dmigrateCode=RESOURCE_NOT_FOUND`. Error messages NEVER
 *   mention the requested URI or whether the resource exists.
 *
 * AP D7 sub-commit 1 leaves the inline-vs-artefactRef byte
 * threshold (Plan-D §5.2) and the strict request-parameter shape
 * (`additionalProperties=false` on `ReadResourceParams`) for
 * follow-up sub-commits — they are independent of the
 * precedence-chain refactor and the capabilities resolver.
 */
internal class ResourcesReadHandler(
    private val stores: ResourceStores,
    private val capabilitiesProvider: () -> Map<String, Any?> = { emptyMap() },
) {

    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    fun read(principal: PrincipalContext, rawUri: String): ReadResourceResult {
        val classification = ResourceErrorPrecedence.classify(rawUri, principal)
        val uri = when (classification) {
            is ResourceClassification.Resolved -> classification.uri
            is ResourceClassification.Failed -> throw classification.error.toResponseError()
        }
        val payload = lookup(principal, uri) ?: throw McpResourceError.ResourceNotFound.toResponseError()
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

    private fun lookup(
        principal: PrincipalContext,
        uri: McpResourceUri,
    ): Map<String, Any?>? = when (uri) {
        is GlobalCapabilitiesResourceUri -> capabilitiesProvider()
            .takeIf { it.isNotEmpty() }
        is TenantResourceUri -> lookupTenantResource(principal, uri)
        // ArtifactChunkResourceUri is the four-segment chunked-read
        // URI. AP D9 wires it onto `artifact_chunk_get`'s resolver
        // so the same Phase-D precedence chain governs both paths.
        // Until then the chunk resolver isn't bound here — the
        // request collapses into the no-oracle not-found branch
        // rather than ever returning a bogus content slice.
        is ArtifactChunkResourceUri -> null
    }

    private fun lookupTenantResource(
        principal: PrincipalContext,
        uri: TenantResourceUri,
    ): Map<String, Any?>? = when (uri.kind) {
        ResourceKind.JOBS -> stores.jobStore.findById(uri.tenantId, uri.id)
            ?.takeIf { it.isReadableBy(principal, uri.tenantId) }
            ?.let(ResourceContentProjector::projectContent)

        ResourceKind.ARTIFACTS -> stores.artifactStore.findById(uri.tenantId, uri.id)
            ?.takeIf { it.isReadableBy(principal, uri.tenantId) }
            ?.let(ResourceContentProjector::projectContent)

        ResourceKind.SCHEMAS -> stores.schemaStore.findById(uri.tenantId, uri.id)
            ?.let(ResourceContentProjector::projectContent)

        ResourceKind.PROFILES -> stores.profileStore.findById(uri.tenantId, uri.id)
            ?.let(ResourceContentProjector::projectContent)

        ResourceKind.DIFFS -> stores.diffStore.findById(uri.tenantId, uri.id)
            ?.let(ResourceContentProjector::projectContent)

        ResourceKind.CONNECTIONS -> stores.connectionStore.findById(uri.tenantId, uri.id)
            ?.let(ResourceContentProjector::projectContent)

        // UPLOAD_SESSIONS is filtered upstream by ResourceErrorPrecedence
        // (Stage 3) — this branch is unreachable because the request
        // already failed with VALIDATION_ERROR. Kept exhaustive so
        // adding a new ResourceKind forces a compile-time decision.
        ResourceKind.UPLOAD_SESSIONS -> null
    }

    private fun McpResourceError.toResponseError(): ResponseErrorException {
        val code = when (this) {
            is McpResourceError.ValidationError -> ResponseErrorCode.InvalidParams.value
            is McpResourceError.TenantScopeDenied -> ResponseErrorCode.InvalidRequest.value
            McpResourceError.ResourceNotFound -> MCP_RESOURCE_NOT_FOUND_CODE
        }
        // Wire-message stays generic for the no-oracle branches:
        // the parse-reason / probe never lands in the message field.
        // The dmigrateCode in error.data is the stable d-migrate
        // discriminator clients should branch on.
        val message = when (this) {
            is McpResourceError.ValidationError -> when {
                // Phase-B kept the wire message as the literal
                // "invalid resource URI" so a caller can't probe
                // grammar paths. Phase-D keeps that contract for
                // grammar failures; UPLOAD_SESSIONS-blocked-kind
                // gets its own distinct message because the kind
                // segment is observable by the client (Plan-D
                // §5.1 lists upload-sessions as not-readable).
                message.startsWith("resource kind '") -> message
                else -> INVALID_URI_MESSAGE
            }
            is McpResourceError.TenantScopeDenied -> "tenant scope denied for requested resource"
            McpResourceError.ResourceNotFound -> "Resource not found"
        }
        val data = mapOf("dmigrateCode" to dmigrateCode)
        return ResponseErrorException(ResponseError(code, message, data))
    }

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
