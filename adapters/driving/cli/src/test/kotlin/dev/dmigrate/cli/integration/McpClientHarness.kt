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

    /**
     * AP 6.24 E3+: the Phase-C wiring this harness's server runs on.
     * Scenario tests pre-stage server-side state (schemas, artefacts,
     * jobs) directly via the in-memory stores instead of always
     * driving the upload flow end-to-end. Both [StdioHarness] and
     * [HttpHarness] expose the same wiring instance their bootstrap
     * was built from.
     */
    val wiring: dev.dmigrate.mcp.registry.PhaseCWiring

    fun initialize(params: InitializeParams = defaultInitializeParams()): InitializeResult

    fun initializedNotification()

    fun toolsList(): ToolsListResult

    fun toolsCall(name: String, arguments: JsonElement?): ToolsCallResult

    /**
     * AP 6.9 / AP 6.24 E6: `resources/read` happy-path. Returns the
     * `result` JsonElement; throws if the server returned a JSON-RPC
     * error. Use [resourcesReadRaw] when the spec needs to inspect
     * the error branch (no-oracle assertions).
     */
    fun resourcesRead(uri: String): JsonElement

    /**
     * AP 6.24 E6: raw `resources/read` for no-oracle scenarios.
     * Returns the full [JsonRpcResponse] so the spec can pin both
     * the success projection and the error envelope (code, scrubbed
     * message class) on a single call. The return type carries
     * `result` and `error` mutually-exclusively per JSON-RPC 2.0.
     */
    fun resourcesReadRaw(uri: String): JsonRpcResponse

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
