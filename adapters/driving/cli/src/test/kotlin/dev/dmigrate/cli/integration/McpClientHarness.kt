package dev.dmigrate.cli.integration

import com.google.gson.JsonElement
import dev.dmigrate.mcp.protocol.InitializeParams
import dev.dmigrate.mcp.protocol.InitializeResult
import dev.dmigrate.mcp.protocol.ToolsCallParams
import dev.dmigrate.mcp.protocol.ToolsCallResult
import dev.dmigrate.mcp.protocol.ToolsListResult
import dev.dmigrate.server.core.principal.PrincipalContext
import java.nio.file.Path

/**
 * AP 6.24: transport-neutral facade for the MCP integration suite.
 * Both [StdioHarness] and [HttpHarness] expose this surface so the
 * scenario runner can iterate `transports.forAll { runScenario(it) }`
 * without duplicating dispatch / assert plumbing per transport.
 *
 * Responsibilities NOT on the surface:
 * - Transport-specific error pre-dispatch (HTTP status / Auth header
 *   handling, stdio token rejection) — those have their own per-
 *   transport asserts in the scenario specs.
 * - Bootstrap / shutdown timing — driven internally by the harness;
 *   the scenario runner only sees [close].
 *
 * @property name short identifier used in test parametrisation
 *   (e.g. `"stdio"`, `"http"`).
 * @property stateDir the MCP server's resolved state-dir for this
 *   harness instance. Tests inspect this directly to verify the
 *   AP-6.21 file-backed layout (`<stateDir>/segments/...`,
 *   `<stateDir>/artifacts/...`, `<stateDir>/assembly/...`).
 * @property principal the principal the server sees on
 *   `tools/call` / `resources/read`. Constructed per-harness so the
 *   stdio principal-from-token derivation does not collide with the
 *   HTTP Bearer-validation result.
 */
internal interface McpClientHarness : AutoCloseable {

    val name: String
    val stateDir: Path
    val principal: PrincipalContext

    fun initialize(params: InitializeParams = defaultInitializeParams()): InitializeResult

    fun initializedNotification()

    fun toolsList(): ToolsListResult

    fun toolsCall(name: String, arguments: JsonElement?): ToolsCallResult

    /**
     * AP 6.9 / AP 6.24 E6: `resources/read` may not yet be wired in
     * the protocol layer; the harness exposes it on the surface so
     * the E6 scenarios can fail fast with a clear "method not found"
     * if the wire-API isn't there. Returns a [JsonElement] (rather
     * than a typed result) so the spec can branch on the JSON-RPC
     * error vs a result body without parsing twice.
     */
    fun resourcesRead(uri: String): JsonElement

    override fun close()

    companion object {
        /**
         * Mirrors the server's pinned MCP protocol version
         * ([dev.dmigrate.mcp.protocol.McpProtocol.MCP_PROTOCOL_VERSION]).
         * The server's `initialize` handler returns
         * `INVALID_PARAMS -32602` for any other value, so the harness
         * cannot diverge.
         */
        const val PROTOCOL_VERSION: String = dev.dmigrate.mcp.protocol.McpProtocol.MCP_PROTOCOL_VERSION

        fun defaultInitializeParams(): InitializeParams = InitializeParams(
            protocolVersion = PROTOCOL_VERSION,
            clientInfo = dev.dmigrate.mcp.protocol.ClientInfo(name = "dmigrate-it-test", version = "0.0.0"),
            capabilities = dev.dmigrate.mcp.protocol.ClientCapabilities(),
        )
    }
}
