package dev.dmigrate.mcp.registry

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import dev.dmigrate.mcp.schema.PhaseBToolSchemas
import dev.dmigrate.mcp.server.McpLimitsConfig
import dev.dmigrate.server.application.error.InternalAgentErrorException
import dev.dmigrate.server.application.error.PayloadTooLargeException
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.principal.PrincipalContext
import org.slf4j.LoggerFactory

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
     *
     * AP 6.23: the four schema-strict tools — `schema_validate`,
     * `schema_compare`, `schema_generate`, `job_status_get` — own
     * their tool-specific truncated/artifactRef fallback in the
     * handler. The generic `{summary, artifactRef, truncated}`
     * envelope below does NOT match their per-tool output schemas
     * (e.g. `schema_validate` requires `valid + summary + findings +
     * truncated`; `job_status_get` requires `jobId + operation +
     * status + ...`). For these tools, an oversize response is a
     * handler-side bug and surfaces as `INTERNAL_AGENT_ERROR` —
     * the tool's own oversize path should have downgraded the
     * payload before it reached the enforcer.
     *
     * If even the artefact-spill fails because the response exceeds
     * `maxArtifactUploadBytes`, we surface that as
     * `INTERNAL_AGENT_ERROR`, not `PAYLOAD_TOO_LARGE`: the client
     * sent a perfectly-sized request, the server's per-handler cap
     * is just smaller than the operator-configured spill cap.
     * Letting `PAYLOAD_TOO_LARGE` bubble would mislead the client
     * into thinking it has a request-side fix.
     */
    fun enforceResponseSize(
        toolName: String,
        principal: PrincipalContext,
        outcome: ToolCallOutcome,
    ): ToolCallOutcome {
        if (outcome !is ToolCallOutcome.Success) return outcome
        val sole = outcome.content.singleOrNull() ?: return outcome
        val text = sole.text ?: return outcome
        // Fast path: UTF-8 is at most 4 bytes/char, so `length * 4`
        // bytes is a hard upper bound. When that already fits, skip
        // the full encode (saves one byte[] alloc + one full UTF-8
        // pass per success response on the dispatch hot path).
        // `Int.toLong()` keeps the multiplication overflow-safe for
        // the worst-case 2^31-1-char string.
        val cap = limits.maxToolResponseBytes
        if (text.length.toLong() * 4 <= cap.toLong()) return outcome
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= cap) return outcome

        // AP 6.23: tools with their own per-output-schema cannot be
        // wrapped in the generic truncated envelope (it would fail
        // their schema's required-field constraint). The handler is
        // expected to downgrade itself; an oversize response here is
        // treated as an internal handler bug. Log size + tool name
        // (NEVER the payload bytes — those may contain secrets)
        // so operators can debug a stuck handler (review N5).
        if (toolName in SCHEMA_AWARE_TOOLS) {
            LOG.warn(
                "schema-aware tool '{}' produced oversize response: {} > {} bytes",
                toolName, bytes.size, cap,
            )
            throw InternalAgentErrorException()
        }

        val artifactUri = try {
            artifactSink.writeReadOnly(
                principal = principal,
                kind = ArtifactKind.OTHER,
                contentType = sole.mimeType ?: "application/json",
                filename = "tool-response-overflow-${toolName}.json",
                content = bytes,
                maxArtifactBytes = limits.maxArtifactUploadBytes,
            )
        } catch (e: PayloadTooLargeException) {
            // The handler produced a response that doesn't even fit
            // the operator's spill cap. Re-raise as
            // INTERNAL_AGENT_ERROR (cause-chained) so the client sees
            // an honest "server-side limit, not your fault" envelope
            // instead of being told their request was too big.
            throw InternalAgentErrorException(cause = e)
        }
        val replacement = mapOf(
            "summary" to "Response of ${bytes.size} bytes exceeded the " +
                "${cap}-byte tool-response limit; full payload moved to artefact.",
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

    // The "artifact_upload" literal is also the registry/scope-mapping
    // key for the segment-upload tool — see PhaseBRegistries,
    // PhaseCRegistries, McpServerConfig.DEFAULT_SCOPE_MAPPING, and
    // PhaseBToolSchemas. Project convention is inline strings; if a
    // future rename happens, all five sites move together.
    private fun capForTool(toolName: String): Int = when (toolName) {
        "artifact_upload" -> limits.maxUploadToolRequestBytes
        else -> limits.maxNonUploadToolRequestBytes
    }

    internal companion object {
        /**
         * AP 6.23: tools whose output schema requires per-tool fields
         * (e.g. `valid`, `dialect`, `status`, …) and therefore cannot
         * be wrapped in the generic `{summary, artifactRef, truncated}`
         * fallback envelope without violating their JSON-Schema. Each
         * of these handlers owns its own oversize path.
         */
        val SCHEMA_AWARE_TOOLS: Set<String> = setOf(
            "schema_validate",
            "schema_compare",
            "schema_generate",
            "job_status_get",
        )

        @JvmStatic
        private val LOG = LoggerFactory.getLogger(ResponseLimitEnforcer::class.java)

        init {
            // Review N4: detect drift between this hardcoded set and
            // the registered tool catalogue. If a tool is renamed in
            // PhaseBToolSchemas without updating SCHEMA_AWARE_TOOLS,
            // the wrong path would silently activate at runtime.
            val unknown = SCHEMA_AWARE_TOOLS.filter { PhaseBToolSchemas.forTool(it) == null }
            require(unknown.isEmpty()) {
                "SCHEMA_AWARE_TOOLS references tools not registered in " +
                    "PhaseBToolSchemas: $unknown"
            }
        }
    }
}
