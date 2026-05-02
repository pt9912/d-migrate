package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.server.McpLimitsConfig
import java.util.UUID

/**
 * AP 6.2: Phase-C `capabilities_list` per
 * `ImpPlan-0.9.6-C.md` §6.2 + §10.
 *
 * Output shape (stable across 0.9.6):
 * ```json
 * {
 *   "mcpProtocolVersion": "2025-11-25",
 *   "dmigrateContractVersion": "v1",
 *   "serverName": "d-migrate",
 *   "tools": [ ... ],
 *   "scopeTable": { ... },
 *   "dialects": [ "POSTGRESQL", "MYSQL", "SQLITE" ],
 *   "formats": [ "json", "yaml" ],
 *   "limits": { "maxToolResponseBytes": 65536, ... },
 *   "executionMeta": { "requestId": "req-…" }
 * }
 * ```
 *
 * Per §6.2, tool schemas are reachable via `tools/list` and would
 * bloat this payload — they stay out. Inline limits and resource
 * fallback hints stay in so agents can plan large-payload paths.
 *
 * The descriptor list is supplied at construction time (NOT via the
 * registry) so [PhaseBRegistries] can build the registry in a single
 * pass without a back-reference cycle.
 *
 * `requestIdProvider` is a function rather than a literal so each
 * `tools/call` invocation gets a fresh correlation id without
 * threading state through the registry. Tests inject a deterministic
 * supplier so the golden of the response stays stable.
 */
internal class CapabilitiesListReadOnlyHandler(
    private val tools: List<ToolDescriptor>,
    private val scopeMapping: Map<String, Set<String>>,
    private val limits: McpLimitsConfig = McpLimitsConfig(),
    private val dialects: List<String> = DatabaseDialect.values().map { it.name },
    private val formats: List<String> = DEFAULT_FORMATS,
    private val requestIdProvider: () -> String = ::generateRequestId,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val payload = mapOf(
            "mcpProtocolVersion" to McpProtocol.MCP_PROTOCOL_VERSION,
            "dmigrateContractVersion" to McpProtocol.DMIGRATE_CONTRACT_VERSION,
            "serverName" to McpProtocol.SERVER_NAME,
            "tools" to tools.map(::projectTool),
            "scopeTable" to invertScopeMapping(scopeMapping),
            "dialects" to dialects,
            "formats" to formats,
            "limits" to projectLimits(limits),
            "executionMeta" to mapOf("requestId" to requestIdProvider()),
        )
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

    private fun projectTool(descriptor: ToolDescriptor): Map<String, Any?> = buildMap {
        put("name", descriptor.name)
        put("title", descriptor.title)
        put("description", descriptor.description)
        put("requiredScopes", descriptor.requiredScopes.sorted())
        if (descriptor.inlineLimits != null) put("inlineLimits", descriptor.inlineLimits)
        if (descriptor.resourceFallbackHint != null) {
            put("resourceFallbackHint", descriptor.resourceFallbackHint)
        }
        if (descriptor.errorCodes.isNotEmpty()) {
            put("errorCodes", descriptor.errorCodes.map { it.name }.sorted())
        }
    }

    /**
     * Inverts `(method -> scopes)` into `(scope -> methods)` so agents
     * can list every method requiring a given scope without scanning
     * the full mapping. Multi-scope tools (a method that demands BOTH
     * scope A AND scope B) appear under both A and B — losing either
     * would mean a client could fail Protected Resource Metadata
     * advertisement (§4.4) for legitimate scope holders.
     *
     * Empty scope sets on a method are skipped: a no-scope method is
     * scope-free per §12.14 (`SCOPE_FREE_METHODS`) and does not belong
     * in the per-scope projection.
     */
    private fun invertScopeMapping(
        mapping: Map<String, Set<String>>,
    ): Map<String, List<String>> {
        val out = sortedMapOf<String, MutableSet<String>>()
        for ((method, scopes) in mapping) {
            for (scope in scopes) {
                out.getOrPut(scope) { sortedSetOf() } += method
            }
        }
        return out.mapValues { (_, methods) -> methods.toList() }
    }

    private fun projectLimits(limits: McpLimitsConfig): Map<String, Number> = mapOf(
        "maxToolResponseBytes" to limits.maxToolResponseBytes,
        "maxNonUploadToolRequestBytes" to limits.maxNonUploadToolRequestBytes,
        "maxInlineSchemaBytes" to limits.maxInlineSchemaBytes,
        "maxUploadToolRequestBytes" to limits.maxUploadToolRequestBytes,
        "maxUploadSegmentBytes" to limits.maxUploadSegmentBytes,
        "maxArtifactChunkBytes" to limits.maxArtifactChunkBytes,
        "maxInlineFindings" to limits.maxInlineFindings,
        "maxArtifactUploadBytes" to limits.maxArtifactUploadBytes,
    )

    companion object {
        /**
         * Canonical neutral-schema formats supported by
         * `:adapters:driven:formats` (`SchemaFileResolver.codecForFormat`).
         * Listed here as a const so `capabilities_list` does not pull
         * the formats module onto the MCP-adapter classpath just for
         * advertisement.
         */
        internal val DEFAULT_FORMATS: List<String> = listOf("json", "yaml")

        /**
         * Server-side correlation id per `spec/ki-mcp.md` §14
         * ("executionMeta.requestId"). Eight hex chars are enough to
         * disambiguate within a session; the audit log keeps the full
         * request envelope if longer correlation is needed.
         */
        private fun generateRequestId(): String = "req-${UUID.randomUUID().toString().take(8)}"
    }
}
