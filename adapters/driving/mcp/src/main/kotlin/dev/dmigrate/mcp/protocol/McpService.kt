package dev.dmigrate.mcp.protocol

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

/**
 * MCP service surface dispatched by lsp4j's `GenericEndpoint`. The
 * methods are wired by their JSON-RPC method names — these strings
 * are part of the MCP wire contract and must match the spec
 * exactly.
 *
 * AP 6.4 implemented `initialize` and the matching
 * `notifications/initialized` no-op. AP 6.8 adds `tools/list` and
 * `tools/call` per §6.8 + §12.8 — `tools/list` advertises the registry
 * descriptors, `tools/call` dispatches into the handler chain.
 *
 * Resource methods (`resources/list`, `resources/templates/list`,
 * `resources/read`) follow in AP 6.9.
 */
interface McpService {

    @JsonRequest("initialize")
    fun initialize(params: InitializeParams): CompletableFuture<InitializeResult>

    @JsonNotification("notifications/initialized")
    fun initialized()

    @JsonRequest("tools/list")
    fun toolsList(params: ToolsListParams?): CompletableFuture<ToolsListResult>

    @JsonRequest("tools/call")
    fun toolsCall(params: ToolsCallParams): CompletableFuture<ToolsCallResult>

    @JsonRequest("resources/list")
    fun resourcesList(params: ResourcesListParams?): CompletableFuture<ResourcesListResult>

    @JsonRequest("resources/templates/list")
    fun resourcesTemplatesList(params: ResourcesTemplatesListParams?): CompletableFuture<ResourcesTemplatesListResult>
}
