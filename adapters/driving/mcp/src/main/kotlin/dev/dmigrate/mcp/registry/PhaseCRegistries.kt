package dev.dmigrate.mcp.registry

import dev.dmigrate.mcp.server.McpServerConfig

/**
 * Phase-C wiring entry point per `ImpPlan-0.9.6-C.md` §6.1.
 *
 * Override semantics worth pinning down:
 * - keys MUST be tool names already registered by [PhaseBRegistries] —
 *   an unknown name would otherwise become a silent `tools/call -32601`
 *   at runtime instead of failing fast at boot.
 * - keys MUST NOT be MCP-protocol method names (`tools/list`,
 *   `resources/read`, ...). Those are dispatched by the protocol layer,
 *   not the tool registry, so an override would silently no-op.
 * - non-overridden tools keep dispatching to [UnsupportedToolHandler]
 *   so the `UNSUPPORTED_TOOL_OPERATION` envelope is preserved while
 *   later phases land their handlers incrementally.
 *
 * Both transports MUST share the SAME registry instance — §6.1
 * acceptance ("Aufrufe nutzen denselben Handler unabhaengig vom
 * Transport"). Build once, pass into both
 * `McpServerBootstrap.startStdio` and `startHttp`.
 */
object PhaseCRegistries {

    fun toolRegistry(
        scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
        handlerOverrides: Map<String, ToolHandler> = emptyMap(),
    ): ToolRegistry {
        if (handlerOverrides.isEmpty()) return PhaseBRegistries.toolRegistry(scopeMapping)

        val protocolHits = handlerOverrides.keys.intersect(PhaseBRegistries.PROTOCOL_METHODS)
        check(protocolHits.isEmpty()) {
            "handlerOverrides target MCP-protocol methods ${protocolHits.sorted()} — " +
                "those are dispatched by the protocol layer, not the tool registry"
        }

        val base = PhaseBRegistries.toolRegistry(scopeMapping)
        val unknown = handlerOverrides.keys.filter { base.find(it) == null }
        check(unknown.isEmpty()) {
            "handlerOverrides target unregistered tools: ${unknown.sorted()} " +
                "(register them via scopeMapping + PhaseBToolSchemas first)"
        }

        val builder = ToolRegistry.builder()
        for (descriptor in base.all()) {
            val handler = handlerOverrides[descriptor.name] ?: base.findHandler(descriptor.name)!!
            builder.register(descriptor, handler)
        }
        return builder.build()
    }
}
