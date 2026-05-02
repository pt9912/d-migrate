package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.mcp.server.McpLimitsConfig
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
import java.util.UUID

/**
 * AP 6.11: `artifact_chunk_get` per `ImpPlan-0.9.6-C.md` §5.5 + §6.11.
 *
 * Reads a single chunk of an artefact. The chunk index is a
 * sequential integer starting at 0; the offset is computed
 * server-side as `chunkIndex * maxArtifactChunkBytes`. The first
 * call without `chunkId` returns chunk `"0"`; subsequent calls
 * follow `nextChunkUri` (which the response carries when more
 * chunks remain).
 *
 * Errors map per the no-oracle pattern §5.6: missing or not-readable
 * artefacts surface uniformly as `RESOURCE_NOT_FOUND` so a client
 * can't distinguish "wrong id" from "no permission to know".
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
    private val requestIdProvider: () -> String = ::generateRequestId,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val obj = JsonArgs.requireObject(context.arguments)
        val artifactId = obj.requireString("artifactId")
        val chunkId = obj.optString("chunkId") ?: "0"
        val chunkIndex = parseChunkIndex(chunkId)

        val record = artifactStore.findById(context.principal.effectiveTenantId, artifactId)
            ?: throw notFound(context.principal, artifactId)
        if (!record.isReadableBy(context.principal)) {
            // No-oracle: a wrong-visibility artefact is indistinguishable
            // from a missing one over the wire.
            throw notFound(context.principal, artifactId)
        }

        val chunkSize = limits.maxArtifactChunkBytes
        val totalBytes = record.managedArtifact.sizeBytes
        val offset = chunkIndex.toLong() * chunkSize.toLong()
        if (offset >= totalBytes && totalBytes > 0) {
            // Out-of-range chunk index for the existing artefact.
            throw notFound(context.principal, artifactId)
        }
        val length = minOf(chunkSize.toLong(), totalBytes - offset).toInt()
        val bytes = if (length == 0) {
            ByteArray(0)
        } else {
            contentStore.openRangeRead(artifactId, offset, length.toLong()).use { it.readAllBytes() }
        }

        val contentType = record.managedArtifact.contentType
        val isText = isTextContentType(contentType)
        val sha256 = sha256Hex(bytes)
        val nextChunkUri = if (offset + length < totalBytes) {
            chunkUri(record.tenantId.value, artifactId, (chunkIndex + 1).toString())
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
            put("executionMeta", mapOf("requestId" to requestIdProvider()))
            if (isText) {
                put("encoding", "text")
                put("text", String(bytes, Charsets.UTF_8))
            } else {
                put("encoding", "base64")
                put("contentBase64", Base64.getEncoder().encodeToString(bytes))
            }
            if (nextChunkUri != null) put("nextChunkUri", nextChunkUri)
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

        private fun generateRequestId(): String =
            "req-${UUID.randomUUID().toString().take(8)}"
    }
}
