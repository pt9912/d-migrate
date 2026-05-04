package dev.dmigrate.mcp.resources

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.protocol.ReadResourceResult
import dev.dmigrate.mcp.protocol.ResourceContents
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.audit.SecretScrubber
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
import java.util.Base64

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
 * AP D7 sub-commit 3 adds the inline-byte enforcement from
 * Plan-D §5.2: every JSON projection's serialised UTF-8 size must
 * stay at or below [McpLimitsConfig.maxInlineResourceContentBytes].
 * Projections that carry an `artifactRef` get stripped to a
 * referral payload (`uri`, `tenantId`, `artifactRef`, plus an
 * `inlineLimitExceeded` marker) so the response stays under the
 * cap while keeping a follow-up pointer. Projections without an
 * artifactRef (capabilities, jobs, connections) cannot build a
 * referral; those collapse to `VALIDATION_ERROR` with a server-
 * side cap-exceeded message because Plan-D §5.2 forbids both an
 * empty/partial response and an inline payload above the limit.
 */
internal class ResourcesReadHandler(
    private val stores: ResourceStores,
    private val capabilitiesProvider: () -> Map<String, Any?> = { emptyMap() },
    private val limits: McpLimitsConfig = McpLimitsConfig(),
) {

    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    fun read(principal: PrincipalContext, rawUri: String): ReadResourceResult {
        val classification = ResourceErrorPrecedence.classify(rawUri, principal)
        val uri = when (classification) {
            is ResourceClassification.Resolved -> classification.uri
            is ResourceClassification.Failed -> throw classification.error.toResponseError()
        }
        val result = if (uri is ArtifactChunkResourceUri) {
            ReadResourceResult(contents = listOf(readArtifactChunk(principal, uri)))
        } else {
            val payload = lookup(principal, uri)
                ?: throw McpResourceError.ResourceNotFound.toResponseError()
            val (text, mime) = serialiseWithInlineCap(uri, payload)
            ReadResourceResult(
                contents = listOf(
                    ResourceContents(
                        uri = uri.render(),
                        mimeType = mime,
                        text = text,
                    ),
                ),
            )
        }
        // Plan-D §5.2 review: the per-content inline cap
        // (maxInlineResourceContentBytes) is necessary but not
        // sufficient — the full envelope (multi-content + the
        // outer ReadResourceResult shell) MUST also fit under
        // maxResourceReadResponseBytes. A future multi-content
        // resolver could otherwise stack several "inline-OK"
        // slices into an oversized response.
        enforceResponseCap(result)
        return result
    }

    /**
     * Plan-D §5.2 hard ceiling on the entire `resources/read`
     * response envelope. Serialises the [ReadResourceResult]
     * via Gson and rejects bodies above
     * [McpLimitsConfig.maxResourceReadResponseBytes] with the
     * same `VALIDATION_ERROR` family the inline-cap path uses.
     */
    private fun enforceResponseCap(result: ReadResourceResult) {
        val rendered = gson.toJson(result)
        val size = utf8Size(rendered)
        if (size > limits.maxResourceReadResponseBytes) {
            throw McpResourceError.ValidationError(
                "resource read response exceeds maxResourceReadResponseBytes " +
                    "($size > ${limits.maxResourceReadResponseBytes})",
            ).toResponseError()
        }
    }

    /**
     * Plan-D §5.2 enforcement: an inline payload's UTF-8 byte size
     * MUST be `<= maxInlineResourceContentBytes`. Returns the
     * serialised body that fits in the inline budget — either the
     * original projection (when small enough) or a stripped
     * referral pointing to the projection's `artifactRef`.
     */
    private fun serialiseWithInlineCap(
        uri: McpResourceUri,
        payload: Map<String, Any?>,
    ): Pair<String, String> {
        val firstAttempt = gson.toJson(payload)
        val firstSize = utf8Size(firstAttempt)
        if (firstSize <= limits.maxInlineResourceContentBytes) {
            return firstAttempt to JSON_MIME
        }
        val artifactRef = payload[FIELD_ARTIFACT_REF] as? String
            ?: throw McpResourceError.ValidationError(
                "resource projection exceeds maxInlineResourceContentBytes " +
                    "($firstSize > ${limits.maxInlineResourceContentBytes}) " +
                    "and the projection has no artifactRef referral path",
            ).toResponseError()
        val referral = mapOf(
            "uri" to uri.render(),
            "tenantId" to payload[FIELD_TENANT_ID],
            "artifactRef" to artifactRef,
            // Diagnostic markers — clients branch on them rather
            // than on field presence, since artifactRef alone is
            // also present on inline projections (Plan-D §5.2 keeps
            // the artifactRef field on the inline metadata to act
            // as a content pointer).
            "inlineLimitExceeded" to true,
            "inlineSizeBytes" to firstSize,
            "inlineLimitBytes" to limits.maxInlineResourceContentBytes,
        )
        return gson.toJson(referral) to JSON_MIME
    }

    private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

    private fun readArtifactChunk(
        principal: PrincipalContext,
        uri: ArtifactChunkResourceUri,
    ): ResourceContents {
        val chunkIndex = parseChunkIndex(uri.chunkId)
        val record = stores.artifactStore.findById(uri.tenantId, uri.artifactId)
            ?: throw McpResourceError.ResourceNotFound.toResponseError()
        if (!record.isReadableBy(principal, uri.tenantId)) {
            throw McpResourceError.ResourceNotFound.toResponseError()
        }
        if (!stores.artifactContentStore.exists(uri.artifactId)) {
            throw McpResourceError.ResourceNotFound.toResponseError()
        }

        val chunkSize = limits.maxArtifactChunkBytes
        val totalBytes = record.managedArtifact.sizeBytes
        val offset = chunkIndex.toLong() * chunkSize.toLong()
        if (chunkIndex > 0 && offset >= totalBytes) {
            throw McpResourceError.ResourceNotFound.toResponseError()
        }
        val length = minOf(chunkSize.toLong(), totalBytes - offset).toInt().coerceAtLeast(0)
        val bytes = if (length == 0) {
            ByteArray(0)
        } else {
            stores.artifactContentStore.openRangeRead(uri.artifactId, offset, length.toLong())
                .use { it.readAllBytes() }
        }

        return if (isTextContentType(record.managedArtifact.contentType)) {
            val text = SecretScrubber.scrub(String(bytes, Charsets.UTF_8))
            ensureInlineFits(text, "text chunk")
            ResourceContents(
                uri = uri.render(),
                mimeType = record.managedArtifact.contentType,
                text = text,
            )
        } else {
            val blob = Base64.getEncoder().encodeToString(bytes)
            ensureInlineFits(blob, "blob chunk")
            ResourceContents(
                uri = uri.render(),
                mimeType = record.managedArtifact.contentType,
                blob = blob,
            )
        }
    }

    private fun parseChunkIndex(raw: String): Int {
        val value = raw.toIntOrNull()
            ?: throw McpResourceError.ValidationError("artifact chunk id must be a non-negative integer")
                .toResponseError()
        if (value < 0) {
            throw McpResourceError.ValidationError("artifact chunk id must be >= 0").toResponseError()
        }
        return value
    }

    private fun ensureInlineFits(body: String, label: String) {
        val size = utf8Size(body)
        if (size > limits.maxInlineResourceContentBytes) {
            throw McpResourceError.ValidationError(
                "resource $label exceeds maxInlineResourceContentBytes " +
                    "($size > ${limits.maxInlineResourceContentBytes})",
            ).toResponseError()
        }
    }

    private fun isTextContentType(contentType: String): Boolean {
        val ct = contentType.substringBefore(";").trim().lowercase()
        return ct.startsWith("text/") || ct in TEXT_APPLICATION_TYPES
    }

    private fun lookup(
        principal: PrincipalContext,
        uri: McpResourceUri,
    ): Map<String, Any?>? = when (uri) {
        is GlobalCapabilitiesResourceUri -> capabilitiesProvider()
            .takeIf { it.isNotEmpty() }
        is TenantResourceUri -> lookupTenantResource(principal, uri)
        // Handled before the JSON-projection path in [read].
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

        // Plan-D §6.4 + §10.10 review: connection-refs honour
        // `allowedPrincipalIds` / `allowedScopes` BOTH on
        // `resources/list` (via the store's `list()` filter) and
        // `resources/read`. Without the per-record `isReadableBy`
        // check, a principal who knows the URI could read a
        // connection that `list` deliberately hides from them.
        // No-oracle: same `RESOURCE_NOT_FOUND` envelope as a
        // genuinely missing id so existence isn't probeable.
        ResourceKind.CONNECTIONS -> stores.connectionStore.findById(uri.tenantId, uri.id)
            ?.takeIf { it.isReadableBy(principal, uri.tenantId) }
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
                // Inline-cap-exceeded errors (Plan-D §5.2) keep
                // their specific message because the cap is a
                // server-side limit already advertised via
                // capabilities_list — there is no grammar oracle
                // to leak.
                message.startsWith("resource kind '") -> message
                message.startsWith("resource projection exceeds") -> message
                message.startsWith("resource read response exceeds") -> message
                message.startsWith("resource ") && message.contains("exceeds maxInlineResourceContentBytes") ->
                    message
                message.startsWith("artifact chunk id ") -> message
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

        private const val FIELD_ARTIFACT_REF: String = "artifactRef"
        private const val FIELD_TENANT_ID: String = "tenantId"

        private val TEXT_APPLICATION_TYPES: Set<String> = setOf(
            "application/json",
            "application/yaml",
            "application/x-yaml",
            "application/xml",
        )

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
