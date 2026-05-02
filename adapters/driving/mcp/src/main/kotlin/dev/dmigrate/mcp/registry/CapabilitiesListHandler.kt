package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.format.SchemaFileResolver
import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.server.McpLimitsConfig

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
 * Tools, scope table, and limits are precomputed in the constructor
 * — they're constant for the handler's lifetime, and `tools/call
 * capabilities_list` runs on every client discovery.
 */
internal class CapabilitiesListReadOnlyHandler(
    tools: List<ToolDescriptor>,
    scopeMapping: Map<String, Set<String>>,
    limits: McpLimitsConfig = McpLimitsConfig(),
    private val dialects: List<String> = DatabaseDialect.entries.map { it.name },
    private val formats: List<String> = SchemaFileResolver.SUPPORTED_FORMATS,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val projectedTools: List<Map<String, Any?>> = tools.map(::projectTool)
    private val scopeTable: Map<String, List<String>> = invertScopeMapping(scopeMapping)
    private val projectedLimits: Map<String, Number> = projectLimits(limits)

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val payload = mapOf(
            "mcpProtocolVersion" to McpProtocol.MCP_PROTOCOL_VERSION,
            "dmigrateContractVersion" to McpProtocol.DMIGRATE_CONTRACT_VERSION,
            "serverName" to McpProtocol.SERVER_NAME,
            "tools" to projectedTools,
            "scopeTable" to scopeTable,
            "dialects" to dialects,
            "formats" to formats,
            "limits" to projectedLimits,
            "executionMeta" to mapOf("requestId" to context.requestId),
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

    /**
     * Mixed `Int`/`Long` projection — `maxArtifactUploadBytes` is
     * `Long` per §4.2 and must NOT be downcast.
     */
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

}
