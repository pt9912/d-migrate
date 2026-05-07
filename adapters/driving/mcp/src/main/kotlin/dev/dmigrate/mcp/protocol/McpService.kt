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
 * AP 6.9 lights up the resource surface: `resources/list`,
 * `resources/templates/list`, `resources/read`. All three are
 * scope-gated on `dmigrate:read` per §12.9.
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

    @JsonRequest("resources/read")
    fun resourcesRead(params: ReadResourceParams): CompletableFuture<ReadResourceResult>

    /**
     * Phase G § 6 G.7: MCP-`prompts/list`. Scope-gated auf
     * `dmigrate:read`. Discovery aller server-seitig registrierten
     * Prompts.
     */
    @JsonRequest("prompts/list")
    fun promptsList(params: PromptsListParams?): CompletableFuture<PromptsListResult>

    /**
     * Phase G § 6 G.7: MCP-`prompts/get`. Scope-gated auf
     * `dmigrate:read`. Validiert Argumente, läuft Hygiene und
     * liefert die Prompt-Nachrichten zurück. **Keine** versteckte
     * Tool-Ausführung (Plan §4.5).
     */
    @JsonRequest("prompts/get")
    fun promptsGet(params: PromptsGetParams): CompletableFuture<PromptsGetResult>
}
