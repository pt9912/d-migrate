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

    /**
     * AP 6.24 E8(C): compact diagnostic dump per
     * `ImpPlan-0.9.6-C.md` §6.24 Z. 2040-2043. Returns a
     * multi-line text report intended for `System.err` printing
     * when a scenario test fails. Contents:
     *
     *  - the last [DIAGNOSTIC_RPC_HISTORY_SIZE] JSON-RPC method
     *    + outcome pairs the harness has seen (oldest first)
     *  - the state-dir file listing (relative paths only, no
     *    content dump — secret-bytes never enter the diagnostic)
     *
     * Out of scope (deferred follow-up):
     *  - server stderr (stdio harness does not pipe stderr today)
     *  - HTTP response body / headers on failure (the harness
     *    throws on non-2xx so error bodies aren't captured)
     */
    fun dumpDiagnostics(reason: String): String

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

        /**
         * Bound on the per-harness JSON-RPC call ring buffer used by
         * [dumpDiagnostics]. Small on purpose: a failing test only
         * needs the tail of the dispatch history.
         */
        const val DIAGNOSTIC_RPC_HISTORY_SIZE: Int = 16

        fun defaultInitializeParams(): InitializeParams = InitializeParams(
            protocolVersion = PROTOCOL_VERSION,
            clientInfo = dev.dmigrate.mcp.protocol.ClientInfo(name = "dmigrate-it-test", version = "0.0.0"),
            capabilities = dev.dmigrate.mcp.protocol.ClientCapabilities(),
        )
    }
}

/**
 * AP 6.24 §6.24 final-review (Z. 1849): the harness's CLIENT-FACING
 * surface (`McpClientHarness`) is restricted to the methods a real
 * MCP client would use — `initialize`, `toolsList`, `toolsCall`,
 * `resourcesRead`, `close` plus metadata. Direct `PhaseCWiring`
 * access is NOT on that surface.
 *
 * Tests that pre-stage server-side state (schemas, jobs, artefacts)
 * still need a wiring handle, but only via this typed test-fixture
 * narrowing helper — never via a public interface property a real
 * client could ever observe. The narrowing fails fast if a future
 * harness type forgets to register here.
 */
internal fun McpClientHarness.testWiring(): dev.dmigrate.mcp.registry.PhaseCWiring = when (this) {
    is StdioHarness -> wiring
    is HttpHarness -> wiring
    else -> error("McpClientHarness.testWiring(): unsupported harness type ${this::class}")
}

/**
 * AP 6.24 E8(C): one entry in the harness diagnostic ring buffer.
 *
 * @property method JSON-RPC method name (`tools/call`,
 *   `resources/read`, `initialize`, ...).
 * @property outcome one-line summary: `ok` for a `result` body,
 *   `err <code>` for a JSON-RPC error, `notification` for a
 *   fire-and-forget call.
 * @property toolName populated for `tools/call` so a failing
 *   scenario test sees WHICH tool ran without inspecting the
 *   request body.
 */
internal data class HarnessRpcExchange(
    val method: String,
    val outcome: String,
    val toolName: String? = null,
) {
    fun render(): String = buildString {
        append(method)
        if (toolName != null) append(" tool=").append(toolName)
        append(" -> ").append(outcome)
    }
}

/**
 * Shared diagnostic-rendering helper used by both [StdioHarness]
 * and [HttpHarness]. Keeps the format identical across transports
 * so a multi-transport scenario emits a uniform dump.
 */
internal object HarnessDiagnostics {

    fun render(
        transportName: String,
        stateDir: java.nio.file.Path,
        history: List<HarnessRpcExchange>,
        reason: String,
    ): String = buildString {
        appendLine("=== $transportName harness diagnostics ===")
        appendLine("reason: $reason")
        appendLine("stateDir: $stateDir")
        appendLine("recent JSON-RPC calls (oldest first, max ${McpClientHarness.DIAGNOSTIC_RPC_HISTORY_SIZE}):")
        if (history.isEmpty()) {
            appendLine("  (no calls recorded)")
        } else {
            history.forEachIndexed { i, ex -> appendLine("  ${i + 1}. ${ex.render()}") }
        }
        appendLine("stateDir files (relative paths, no content):")
        val files = listStateDirFiles(stateDir)
        if (files.isEmpty()) {
            appendLine("  (empty)")
        } else {
            files.forEach { appendLine("  $it") }
        }
        append("=== end $transportName diagnostics ===")
    }

    private fun listStateDirFiles(stateDir: java.nio.file.Path): List<String> {
        if (!java.nio.file.Files.exists(stateDir)) return emptyList()
        // No content dump — only relative paths so a secret-byte
        // artefact body never enters the diagnostic stream.
        return java.nio.file.Files.walk(stateDir).use { stream ->
            stream
                .filter { java.nio.file.Files.isRegularFile(it) }
                .map { stateDir.relativize(it).toString() }
                .sorted()
                .toList()
        }
    }
}
