package dev.dmigrate.mcp.protocol

import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase B AP 6.4 initialize handler.
 *
 * Validates the client's `protocolVersion`, returns the negotiated
 * server capabilities, and remembers the negotiated version for
 * follow-up checks (the actual header validation lives in AP 6.5
 * on the HTTP transport).
 *
 * Wrong `protocolVersion` is mapped to JSON-RPC error `-32602`
 * (Invalid params), per §12.8 — `-32001` is reserved for the
 * "session has different version than initialize" follow-up case
 * which AP 6.5 owns.
 */
class McpServiceImpl(
    private val serverVersion: String,
) : McpService {

    private val negotiated = AtomicReference<String?>(null)

    /** Negotiated `protocolVersion` after a successful initialize, or null. */
    fun negotiatedProtocolVersion(): String? = negotiated.get()

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        if (params.protocolVersion != McpProtocol.MCP_PROTOCOL_VERSION) {
            val msg = "unsupported protocolVersion '${params.protocolVersion}'; " +
                "server requires '${McpProtocol.MCP_PROTOCOL_VERSION}'"
            val err = ResponseError(ResponseErrorCode.InvalidParams, msg, null)
            return CompletableFuture.failedFuture(ResponseErrorException(err))
        }
        negotiated.set(params.protocolVersion)
        val result = InitializeResult(
            protocolVersion = McpProtocol.MCP_PROTOCOL_VERSION,
            capabilities = ServerCapabilities(),
            serverInfo = ServerInfo(name = McpProtocol.SERVER_NAME, version = serverVersion),
        )
        return CompletableFuture.completedFuture(result)
    }

    override fun initialized() {
        // Notification — no response. Phase C uses this hook to flip
        // session into "ready" once tool registries exist.
    }
}
