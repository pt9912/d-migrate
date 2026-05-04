package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.core.principal.PrincipalContext
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ArtifactStore
import java.security.MessageDigest
import java.util.Base64

/**
 * AP 6.11 + AP D9: `artifact_chunk_get` per `ImpPlan-0.9.6-C.md`
 * §5.5 + `ImpPlan-0.9.6-D.md` §10.9.
 *
 * Reads a single chunk of an artefact. The chunk index is a
 * sequential integer starting at 0; the offset is computed
 * server-side as `chunkIndex * maxArtifactChunkBytes`. The first
 * call without `chunkId` / `nextChunkCursor` returns chunk `"0"`;
 * subsequent calls follow either the HMAC-sealed
 * `nextChunkCursor` (Plan-D Tool-Pfad) or the `nextChunkUri`
 * (Plan-D `resources/read`-Pfad).
 *
 * AP D9 changes:
 * - The response now carries an HMAC-sealed `nextChunkCursor`
 *   alongside `nextChunkUri` so a Tool-Caller stays on the
 *   `tools/call` contract instead of jumping to `resources/read`.
 *   Both fields are explicit `null` on the last chunk per the
 *   §5.5 invariant.
 * - The naked `chunkId` integer is still accepted as input
 *   (Phase-C compatibility, Plan-D §10.9 "befristete Legacy-
 *   Eingabe"), but the response no longer emits a `nextChunkId`
 *   field — Plan-D §10.9 forbids it.
 * - Cursor binding ties (tenant, artifactId, chunkSize) so a
 *   sealed cursor minted for artefact A in tenant X cannot be
 *   replayed against artefact B, tenant Y, or with a different
 *   chunkSize.
 *
 * Errors map per the no-oracle pattern §5.6: missing or not-readable
 * artefacts surface uniformly as `RESOURCE_NOT_FOUND` so a client
 * can't distinguish "wrong id" from "no permission to know".
 * Manipulated `nextChunkCursor` collapses to `VALIDATION_ERROR`.
 *
 * Encoding policy: any `text/...` MIME type and the canonical text
 * application types (`application/json`, `application/yaml`,
 * `application/xml`, `application/x-yaml`) are returned as
 * `encoding="text"` with the decoded UTF-8 string in `text`.
 * Everything else is `base64` with the bytes in `contentBase64`.
 * `sha256` and `lengthBytes` always describe the decoded raw bytes
 * regardless of encoding.
 */
internal class ArtifactChunkGetHandler(
    private val artifactStore: ArtifactStore,
    private val contentStore: ArtifactContentStore,
    private val limits: McpLimitsConfig,
    private val cursorCodec: SealedChunkCursor? = null,
) : ToolHandler {

    // serializeNulls is load-bearing: spec/ki-mcp.md §5.5 line 471
    // mandates `nextChunkUri: null` on the last chunk rather than
    // omitting the field. Without `serializeNulls`, Gson silently
    // drops the null entry from the response map.
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val obj = JsonArgs.requireObject(context.arguments)
        val artifactId = obj.requireString("artifactId")
        val chunkSize = limits.maxArtifactChunkBytes
        // AP D9: prefer the HMAC-sealed `nextChunkCursor` over the
        // naked `chunkId` integer. Both inputs MUST NOT be set on
        // the same call — a client should commit to one wire shape.
        val nakedChunkId = obj.optString("chunkId")
        val sealedCursor = obj.optString("nextChunkCursor")
        if (!nakedChunkId.isNullOrBlank() && !sealedCursor.isNullOrBlank()) {
            throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "input",
                        "exactly one of 'chunkId' (legacy) or 'nextChunkCursor' (Phase-D) is allowed",
                    ),
                ),
            )
        }
        val chunkIndex = when {
            !sealedCursor.isNullOrBlank() -> resolveSealedCursor(
                sealedCursor, context.principal.effectiveTenantId, artifactId, chunkSize,
            )
            !nakedChunkId.isNullOrBlank() -> parseChunkIndex(nakedChunkId)
            else -> 0
        }

        val record = artifactStore.findById(context.principal.effectiveTenantId, artifactId)
            ?: throw notFound(context.principal, artifactId)
        if (!record.isReadableBy(context.principal)) {
            // No-oracle: a wrong-visibility artefact is indistinguishable
            // from a missing one over the wire.
            throw notFound(context.principal, artifactId)
        }

        val totalBytes = record.managedArtifact.sizeBytes
        val offset = chunkIndex.toLong() * chunkSize.toLong()
        // Empty artefact (totalBytes == 0) keeps chunk 0 valid as a
        // zero-byte read; chunks 1+ are out of range. Non-empty
        // artefacts reject any chunk whose offset would land beyond
        // the byte stream.
        if (chunkIndex > 0 && offset >= totalBytes) {
            throw notFound(context.principal, artifactId)
        }
        val length = minOf(chunkSize.toLong(), totalBytes - offset).toInt().coerceAtLeast(0)
        val bytes = if (length == 0) {
            ByteArray(0)
        } else {
            contentStore.openRangeRead(artifactId, offset, length.toLong()).use { it.readAllBytes() }
        }

        val contentType = record.managedArtifact.contentType
        val isText = isTextContentType(contentType)
        val sha256 = sha256Hex(bytes)
        val hasMore = offset + length < totalBytes
        val nextChunkUri = if (hasMore) {
            chunkUri(record.tenantId.value, artifactId, (chunkIndex + 1).toString())
        } else {
            null
        }
        // Plan-D §10.9: when a codec is wired (production path),
        // emit the HMAC-sealed continuation cursor alongside the
        // resource-URI form. Phase-B-only deployments without a
        // configured codec emit `null` and rely on `nextChunkUri`
        // for follow-ups.
        val nextChunkCursor = if (hasMore) {
            cursorCodec?.seal(record.tenantId, artifactId, chunkSize, chunkIndex + 1)
        } else {
            null
        }

        val payload = buildMap<String, Any?> {
            put("artifactId", artifactId)
            put("resourceUri", record.resourceUri.render())
            put("chunkId", chunkIndex.toString())
            put("offset", offset)
            put("lengthBytes", length)
            put("contentType", contentType)
            put("sha256", sha256)
            put("executionMeta", mapOf("requestId" to context.requestId))
            if (isText) {
                put("encoding", "text")
                // §6.24 final-review: defense-in-depth scrub on the
                // wire-visible text projection. Server-emitted DDL
                // artefacts are already scrubbed at write-time
                // (SchemaGenerateHandler); user-uploaded text was
                // not, and the chunk_get response is the surface a
                // client agent would re-read into a prompt. The
                // base64 branch is left unchanged — opaque binary
                // bytes don't get pattern-scrubbed safely. `sha256`
                // continues to describe the raw stored bytes per
                // §5.5, so a client doing byte-level integrity
                // checks against the original artifact metadata
                // sees consistent state.
                put("text", SecretScrubber.scrub(String(bytes, Charsets.UTF_8)))
            } else {
                put("encoding", "base64")
                put("contentBase64", Base64.getEncoder().encodeToString(bytes))
            }
            // §5.5 line 471: `nextChunkUri` is mandatory and is
            // explicitly `null` on the last chunk — emit it always.
            put("nextChunkUri", nextChunkUri)
            // Plan-D §10.9: Phase-D output ALWAYS carries
            // nextChunkCursor (null on last chunk, sealed string
            // otherwise) when a codec is wired. Phase-B-only
            // deployments emit null on every chunk — clients pick
            // up `nextChunkUri`.
            put("nextChunkCursor", nextChunkCursor)
        }
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(payload),
                    mimeType = "application/json",
                ),
            ),
        )
    }

    /**
     * Plan-D §10.9: decode a sealed continuation cursor and return
     * the next chunk index. The codec verifies (tenant, artifactId,
     * chunkSize) so a cursor minted for a different artefact /
     * tenant / chunkSize collapses to `VALIDATION_ERROR` via the
     * thrown [ValidationErrorException]. Phase-B-only deployments
     * without a wired codec reject any client-supplied
     * `nextChunkCursor` rather than silently restarting at chunk 0.
     */
    private fun resolveSealedCursor(
        sealed: String,
        tenantId: dev.dmigrate.server.core.principal.TenantId,
        artifactId: String,
        chunkSize: Int,
    ): Int {
        val codec = cursorCodec
            ?: throw ValidationErrorException(
                listOf(
                    ValidationViolation(
                        "nextChunkCursor",
                        "cursor verification is not configured on this server",
                    ),
                ),
            )
        return codec.unseal(sealed, tenantId, artifactId, chunkSize)
    }

    @Suppress("SwallowedException")
    private fun parseChunkIndex(raw: String): Int {
        // SwallowedException: only the sanitised "must be a non-
        // negative integer" message reaches the client; the JDK's
        // NumberFormatException carries no useful detail.
        val value = try {
            raw.toInt()
        } catch (e: NumberFormatException) {
            throw ValidationErrorException(
                listOf(ValidationViolation("chunkId", "must be a non-negative integer")),
            )
        }
        if (value < 0) {
            throw ValidationErrorException(
                listOf(ValidationViolation("chunkId", "must be >= 0")),
            )
        }
        return value
    }

    private fun notFound(principal: PrincipalContext, artifactId: String): ResourceNotFoundException =
        ResourceNotFoundException(
            ServerResourceUri(principal.effectiveTenantId, ResourceKind.ARTIFACTS, artifactId),
        )

    private companion object {
        private val TEXT_APPLICATION_TYPES: Set<String> = setOf(
            "application/json",
            "application/yaml",
            "application/x-yaml",
            "application/xml",
        )

        fun isTextContentType(contentType: String): Boolean {
            val ct = contentType.substringBefore(";").trim().lowercase()
            return ct.startsWith("text/") || ct in TEXT_APPLICATION_TYPES
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun chunkUri(tenantId: String, artifactId: String, chunkId: String): String =
            "dmigrate://tenants/$tenantId/artifacts/$artifactId/chunks/$chunkId"
    }
}
