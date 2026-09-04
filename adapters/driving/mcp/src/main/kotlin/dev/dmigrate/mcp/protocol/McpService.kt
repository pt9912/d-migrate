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
 * LF-012 / LN-027 / LN-028 / LN-038 implemented `initialize` and the matching
 * `notifications/initialized` no-op. LF-012 / LN-027 / LN-028 / LN-038 adds `tools/list` and
 * `tools/call` per §6.8 + §12.8 — `tools/list` advertises the registry
 * descriptors, `tools/call` dispatches into the handler chain.
 *
 * LF-012 / LN-027 / LN-028 / LN-038 lights up the resource surface: `resources/list`,
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
     * ImpPlan-1.2.0-mcp-policy-file-and-connections-list.md Slice B:
     * lists configured connections, optionally with a live
     * reachability check (`checkLive`). Scope-gated on
     * `dmigrate:admin` (per `McpServerConfig.DEFAULT_SCOPE_MAPPING`) —
     * a protocol method like `resources/list`, not a `tools/call` tool
     * (excluded from `tools/list` via
     * `McpContractRegistries.PROTOCOL_METHODS`).
     */
    @JsonRequest("connections/list")
    fun connectionsList(params: ConnectionsListParams?): CompletableFuture<ConnectionsListResult>

    /**
     * LF-017 / LF-024 / LN-030 / LN-031: MCP-`prompts/list`. Scope-gated auf
     * `dmigrate:read`. Discovery aller server-seitig registrierten
     * Prompts.
     */
    @JsonRequest("prompts/list")
    fun promptsList(params: PromptsListParams?): CompletableFuture<PromptsListResult>

    /**
     * LF-017 / LF-024 / LN-030 / LN-031: MCP-`prompts/get`. Scope-gated auf
     * `dmigrate:read`. Validiert Argumente, läuft Hygiene und
     * liefert die Prompt-Nachrichten zurück. **Keine** versteckte
     * Tool-Ausführung (LF-017 / LF-024 / LN-030 / LN-031).
     */
    @JsonRequest("prompts/get")
    fun promptsGet(params: PromptsGetParams): CompletableFuture<PromptsGetResult>
}
