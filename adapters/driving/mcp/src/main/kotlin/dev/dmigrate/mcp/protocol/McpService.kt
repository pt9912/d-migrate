package dev.dmigrate.mcp.protocol

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

/**
 * MCP service surface dispatched by lsp4j's `GenericEndpoint`. The
 * methods are wired by their JSON-RPC method names — these strings
 * are part of the MCP wire contract and must match the spec
 * exactly. AP 6.4 only implements `initialize` and the matching
 * `notifications/initialized` no-op; tools/resources methods follow
 * in AP 6.8/6.9.
 */
interface McpService {

    @JsonRequest("initialize")
    fun initialize(params: InitializeParams): CompletableFuture<InitializeResult>

    @JsonNotification("notifications/initialized")
    fun initialized()
}
