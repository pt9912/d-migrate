package dev.dmigrate.mcp.registry

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalContext

/**
 * AP 6.19: centralised request- and response-byte enforcement for the
 * Phase-C `tools/call` dispatch path.
 *
 * Request side (pre-dispatch): the serialized `arguments` JSON must
 * fit `maxNonUploadToolRequestBytes` for every read-only tool and
 * `maxUploadToolRequestBytes` for `artifact_upload`. Overshoot
 * surfaces as a structured `PAYLOAD_TOO_LARGE` so the client gets
 * the limit value back in `details` (and not an opaque
 * `INTERNAL_AGENT_ERROR` from a downstream truncation).
 *
 * Response side (post-dispatch): the rendered tool response must fit
 * `maxToolResponseBytes`. When the handler legitimately produces a
 * larger payload (e.g. a `schema_validate` finding storm that the
 * handler itself didn't downgrade), the enforcer writes the full
 * payload to an artefact and returns a truncated success envelope
 * carrying `summary`, `artifactRef`, and `truncated=true`. This
 * matches the §5.5 + AP 6.13 fallback contract every handler with
 * its own oversize path already follows; the enforcer is the safety
 * net that catches everything else.
 */
class ResponseLimitEnforcer internal constructor(
    private val limits: McpLimitsConfig,
    private val artifactSink: ArtifactSink,
) {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * Validates the byte size of `arguments` against the per-tool
     * cap. Throws [PayloadTooLargeException] when the limit is
     * exceeded; the caller's existing error mapper turns that into a
     * `PAYLOAD_TOO_LARGE` envelope with the limit in `details`.
     */
    fun enforceRequestSize(toolName: String, arguments: JsonElement?) {
        val bytes = arguments?.let { gson.toJson(it).toByteArray(Charsets.UTF_8).size } ?: 0
        val cap = capForTool(toolName)
        if (bytes > cap) {
            throw PayloadTooLargeException(
                actualBytes = bytes.toLong(),
                maxBytes = cap.toLong(),
            )
        }
    }

    /**
     * Returns [outcome] unchanged when it already fits or carries a
     * non-text payload the enforcer can't measure. When a `Success`
     * outcome's serialized text exceeds [McpLimitsConfig.maxToolResponseBytes],
     * the full payload is written to a tenant-visible artefact and
     * the call is rewritten to a truncated success envelope. Error
     * outcomes pass through untouched — error envelopes are bounded
     * by construction (`code/message/details/requestId`) and never
     * carry the kind of body this code path needs to spill.
     */
    fun enforceResponseSize(
        toolName: String,
        principal: PrincipalContext,
        outcome: ToolCallOutcome,
    ): ToolCallOutcome {
        if (outcome !is ToolCallOutcome.Success) return outcome
        val sole = outcome.content.singleOrNull() ?: return outcome
        val text = sole.text ?: return outcome
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= limits.maxToolResponseBytes) return outcome

        val artifactUri = artifactSink.writeReadOnly(
            principal = principal,
            kind = ArtifactKind.OTHER,
            contentType = sole.mimeType ?: "application/json",
            filename = "tool-response-overflow-${toolName}.json",
            content = bytes,
            maxArtifactBytes = limits.maxArtifactUploadBytes,
        )
        val replacement = mapOf(
            "summary" to "Response of ${bytes.size} bytes exceeded the " +
                "${limits.maxToolResponseBytes}-byte tool-response limit; " +
                "full payload moved to artefact.",
            "artifactRef" to artifactUri.render(),
            "truncated" to true,
        )
        return ToolCallOutcome.Success(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = gson.toJson(replacement),
                    mimeType = "application/json",
                ),
            ),
        )
    }

    private fun capForTool(toolName: String): Int = when (toolName) {
        UPLOAD_TOOL_NAME -> limits.maxUploadToolRequestBytes
        else -> limits.maxNonUploadToolRequestBytes
    }

    private companion object {
        const val UPLOAD_TOOL_NAME = "artifact_upload"
    }
}
