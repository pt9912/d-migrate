package dev.dmigrate.mcp.registry

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.protocol.McpProtocol

/**
 * Phase B's only fachlicher Tool-Handler per
 * `ImpPlan-0.9.6-B.md` §12.11. Surfaces the static contract — tool
 * names, scope mapping, and the d-migrate contract version — so
 * agents can negotiate without touching stores or drivers.
 *
 * Output shape (stable across 0.9.6):
 * ```json
 * {
 *   "mcpProtocolVersion": "2025-11-25",
 *   "dmigrateContractVersion": "v1",
 *   "serverName": "d-migrate",
 *   "tools": [ { "name", "description", "requiredScopes", ... }, ... ],
 *   "scopeTable": { "<scope>": ["<method>", ...], ... }
 * }
 * ```
 *
 * The handler intentionally does NOT include the JSON schemas — they
 * are reachable via `tools/list` and would bloat the
 * `capabilities_list` payload. Inline limits and resource fallback
 * hints are forwarded so agents can plan large-payload paths.
 *
 * The descriptor list is supplied at construction time (NOT via the
 * registry) so [PhaseBRegistries] can build the registry in a single
 * pass without a back-reference cycle.
 */
internal class CapabilitiesListReadOnlyHandler(
    private val tools: List<ToolDescriptor>,
    private val scopeMapping: Map<String, Set<String>>,
) : ToolHandler {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val payload = mapOf(
            "mcpProtocolVersion" to McpProtocol.MCP_PROTOCOL_VERSION,
            "dmigrateContractVersion" to McpProtocol.DMIGRATE_CONTRACT_VERSION,
            "serverName" to McpProtocol.SERVER_NAME,
            "tools" to tools.map(::projectTool),
            "scopeTable" to invertScopeMapping(scopeMapping),
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
}
