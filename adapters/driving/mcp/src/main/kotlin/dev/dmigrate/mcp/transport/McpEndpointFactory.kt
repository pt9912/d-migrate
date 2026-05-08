package dev.dmigrate.mcp.transport

import dev.dmigrate.mcp.protocol.McpService
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.services.GenericEndpoint
import org.eclipse.lsp4j.jsonrpc.services.ServiceEndpoints

/**
 * Builds the lsp4j JSON-RPC pieces shared by stdio and HTTP transports:
 *
 * - [MessageJsonHandler] — Gson-based serializer with the MCP method
 *   table (Initialize today, more in AP 6.8/6.9).
 * - [GenericEndpoint] — reflection-based dispatcher that invokes the
 *   `@JsonRequest`/`@JsonNotification` methods on the local service.
 * - [RemoteEndpoint] — combines local dispatch with an outbound
 *   message consumer (the wire writer); used by both transports.
 *
 * Stdio loops one [RemoteEndpoint] per process. HTTP creates one per
 * request because each POST is a single dispatched message (AP 6.5
 * adds session reuse via `MCP-Session-Id`).
 */
internal object McpEndpointFactory {

    fun jsonHandler(): MessageJsonHandler {
        // ServiceEndpoints reads @JsonRequest / @JsonNotification annotations
        // from the McpService interface to register supported methods.
        val supportedMethods = ServiceEndpoints.getSupportedMethods(McpService::class.java)
        return MessageJsonHandler(supportedMethods)
    }

    fun remoteEndpoint(
        localService: McpService,
        outboundConsumer: MessageConsumer,
    ): RemoteEndpoint = remoteEndpoint(GenericEndpoint(localService), outboundConsumer)

    /**
     * Variant that takes a pre-built [Endpoint] (typically a cached
     * `GenericEndpoint` held by `SessionState`) — avoids the
     * reflection scan that `GenericEndpoint(service)` runs in its
     * constructor.
     */
    fun remoteEndpoint(
        localEndpoint: Endpoint,
        outboundConsumer: MessageConsumer,
    ): RemoteEndpoint = RemoteEndpoint(outboundConsumer, localEndpoint)
}
