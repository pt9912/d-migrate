package dev.dmigrate.mcp.protocol

import com.google.gson.GsonBuilder
import dev.dmigrate.mcp.auth.ScopeChecker
import dev.dmigrate.mcp.registry.PhaseBRegistries
import dev.dmigrate.mcp.registry.ResourceRegistry
import dev.dmigrate.mcp.registry.ResourceTemplateDescriptor
import dev.dmigrate.mcp.registry.ResponseLimitEnforcer
import dev.dmigrate.mcp.registry.ToolCallContext
import dev.dmigrate.mcp.registry.ToolCallOutcome
import dev.dmigrate.mcp.registry.ToolContent
import dev.dmigrate.mcp.registry.ToolDescriptor
import dev.dmigrate.mcp.registry.ToolRegistry
import dev.dmigrate.mcp.resources.ResourceStores
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.server.application.error.ForbiddenPrincipalException
import dev.dmigrate.mcp.resources.ResourcesListCursor
import dev.dmigrate.mcp.resources.ResourcesListHandler
import dev.dmigrate.server.application.error.AuthRequiredException
import dev.dmigrate.server.application.error.DefaultErrorMapper
import dev.dmigrate.server.application.error.ErrorMapper
import dev.dmigrate.server.core.principal.PrincipalContext
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase B initialize / tools handler per
 * `ImpPlan-0.9.6-B.md` §6.4 + §6.8 + §12.8 + §12.11 + §12.16.
 *
 * AP 6.4: Validates the client's `protocolVersion` and remembers the
 * negotiated version. Wrong `protocolVersion` is mapped to JSON-RPC
 * error `-32602` (Invalid params), per §12.8.
 *
 * AP 6.8: Adds `tools/list` and `tools/call`.
 * - `tools/list` projects [toolRegistry] into MCP shape.
 * - `tools/call` dispatches via the registry handler. Unknown tool
 *   names raise JSON-RPC `-32601` (Method not found, §12.8); known
 *   tools without an implementation produce a successful `tools/call`
 *   transport carrying `isError=true` with a `ToolErrorEnvelope`
 *   projection from [errorMapper].
 *
 * The current principal is held in an `AtomicReference` and seeded by
 * [initialPrincipal]. stdio sets it once at bootstrap; HTTP rebinds it
 * per request via [bindPrincipal] from `McpHttpRoute` so §12.14
 * "per-request validation is source-of-truth" survives the dispatch.
 * Phase B's only handler — `capabilities_list` — does not consult the
 * principal, but the scaffolding is here so Phase C/D handlers drop
 * in unchanged.
 */
class McpServiceImpl(
    private val serverVersion: String,
    private val toolRegistry: ToolRegistry = ToolRegistry.builder().build(),
    initialPrincipal: PrincipalContext? = null,
    private val errorMapper: ErrorMapper = DefaultErrorMapper(),
    resourceStores: ResourceStores = ResourceStores.empty(),
    private val resourceRegistry: ResourceRegistry = PhaseBRegistries.resourceRegistry(),
    private val scopeMapping: Map<String, Set<String>> = McpServerConfig.DEFAULT_SCOPE_MAPPING,
    private val responseLimitEnforcer: ResponseLimitEnforcer? = null,
) : McpService {

    private val negotiated = AtomicReference<String?>(null)
    private val currentPrincipal = AtomicReference(initialPrincipal)
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val resourcesListHandler = ResourcesListHandler(resourceStores)

    /** Negotiated `protocolVersion` after a successful initialize, or null. */
    fun negotiatedProtocolVersion(): String? = negotiated.get()

    /**
     * Updates the principal used by subsequent `tools/call` dispatches
     * on this service instance. Called by the HTTP route between
     * Bearer-validation and `tools/call` dispatch (§12.14: per-request
     * Bearer validation is source-of-truth — the McpServiceImpl
     * principal must reflect the latest validated request, not the
     * Initialize-time snapshot).
     *
     * Stdio does not call this method after bootstrap; the principal
     * stays bound to the validated stdio token (§12.15).
     */
    fun bindPrincipal(principal: PrincipalContext?) {
        currentPrincipal.set(principal)
    }

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        if (params.protocolVersion != McpProtocol.MCP_PROTOCOL_VERSION) {
            val msg = "unsupported protocolVersion '${params.protocolVersion}'; " +
                "server requires '${McpProtocol.MCP_PROTOCOL_VERSION}'"
            val err = ResponseError(ResponseErrorCode.InvalidParams, msg, null)
            return CompletableFuture.failedFuture(ResponseErrorException(err))
        }
        negotiated.set(params.protocolVersion)
        val capabilities = ServerCapabilities(
            // §5.3: tools lit up in AP 6.8, resources in AP 6.9.
            // listChanged stays false until subscriptions ship.
            tools = mapOf("listChanged" to false),
            resources = mapOf("listChanged" to false, "subscribe" to false),
        )
        val result = InitializeResult(
            protocolVersion = McpProtocol.MCP_PROTOCOL_VERSION,
            capabilities = capabilities,
            serverInfo = ServerInfo(name = McpProtocol.SERVER_NAME, version = serverVersion),
        )
        return CompletableFuture.completedFuture(result)
    }

    override fun initialized() {
        // Notification — no response. Phase C uses this hook to flip
        // session into "ready" once tool registries exist.
    }

    override fun toolsList(params: ToolsListParams?): CompletableFuture<ToolsListResult> {
        // §12.9 + §12.14: tools/list requires dmigrate:read. The HTTP
        // route checks this upstream (and gets a 403 + scope-challenge
        // when it fails); stdio reaches the service directly so we
        // re-check here. Two layers, one truth.
        enforceScope("tools/list")?.let { return CompletableFuture.failedFuture(it) }
        val tools = toolRegistry.all().map(::toMetadata)
        return CompletableFuture.completedFuture(ToolsListResult(tools = tools, nextCursor = null))
    }

    override fun toolsCall(params: ToolsCallParams): CompletableFuture<ToolsCallResult> {
        val handler = toolRegistry.findHandler(params.name)
            ?: return failedWithMethodNotFound("unknown tool '${params.name}'")
        val principal = currentPrincipal.get() ?: return failedAuthRequiredEnvelope(params.name)
        // §12.9: scope-check uses the actual tool name (not the
        // method name "tools/call"). FORBIDDEN_PRINCIPAL surfaces as
        // an isError envelope per §12.8 — `tools/call` errors live on
        // the tool-result wire, not the JSON-RPC layer.
        scopeViolation(params.name, principal)?.let { required ->
            return CompletableFuture.completedFuture(forbiddenPrincipalEnvelope(principal, params.name, required))
        }
        val context = ToolCallContext(
            name = params.name,
            arguments = params.arguments,
            principal = principal,
        )
        return CompletableFuture.completedFuture(dispatch(handler, context))
    }

    override fun resourcesList(params: ResourcesListParams?): CompletableFuture<ResourcesListResult> {
        val principal = currentPrincipal.get()
            ?: return CompletableFuture.failedFuture(
                ResponseErrorException(
                    ResponseError(ResponseErrorCode.InvalidRequest, "principal not bound", null),
                ),
            )
        // §12.9: resources/list requires dmigrate:read.
        scopeViolation("resources/list", principal)?.let { required ->
            return CompletableFuture.failedFuture(scopeJsonRpcError("resources/list", required))
        }
        val cursor = try {
            ResourcesListCursor.decode(params?.cursor)
        } catch (e: IllegalArgumentException) {
            return CompletableFuture.failedFuture(
                ResponseErrorException(
                    ResponseError(ResponseErrorCode.InvalidParams, e.message ?: "invalid cursor", null),
                ),
            )
        }
        return CompletableFuture.completedFuture(resourcesListHandler.list(principal, cursor))
    }

    override fun resourcesTemplatesList(
        params: ResourcesTemplatesListParams?,
    ): CompletableFuture<ResourcesTemplatesListResult> {
        // §4.7 + §6.9: templates come from the shared
        // [resourceRegistry] so stdio and HTTP can never advertise
        // divergent template sets. The wire shape (`ResourceTemplate`)
        // is a strict subset of the registry's
        // `ResourceTemplateDescriptor` (no `requiredScopes` on the
        // wire — Phase B's clients don't need it for templates).
        // Phase B publishes a static set; no pagination needed,
        // cursor is accepted but ignored.
        enforceScope("resources/templates/list")?.let { return CompletableFuture.failedFuture(it) }
        return CompletableFuture.completedFuture(
            ResourcesTemplatesListResult(
                resourceTemplates = resourceRegistry.templates().map(::toWireTemplate),
                nextCursor = null,
            ),
        )
    }

    /**
     * Returns the missing-scope-violation set if [principal] cannot
     * call [method], or `null` if the call is permitted.
     * `notifications/initialized` and `initialize` are scope-free
     * per §12.14 and always return `null`.
     */
    private fun scopeViolation(method: String, principal: PrincipalContext): Set<String>? {
        if (ScopeChecker.isScopeFree(method)) return null
        val required = ScopeChecker.requiredScopes(method, scopeMapping)
        return if (ScopeChecker.isSatisfied(principal.scopes, required)) null else required
    }

    /**
     * Service-layer enforcement helper for non-tools/call methods.
     * Returns a `ResponseErrorException`-future-payload (the JSON-RPC
     * error to fail the request with) if the principal is missing or
     * scope-deficient; `null` if the call is permitted.
     *
     * §12.8: resource/protocol-method errors are JSON-RPC errors, not
     * tool-result envelopes.
     */
    private fun enforceScope(method: String): ResponseErrorException? {
        val principal = currentPrincipal.get()
            ?: return ResponseErrorException(
                ResponseError(ResponseErrorCode.InvalidRequest, "principal not bound", null),
            )
        val required = scopeViolation(method, principal) ?: return null
        return scopeJsonRpcError(method, required)
    }

    private fun scopeJsonRpcError(method: String, required: Set<String>): ResponseErrorException =
        ResponseErrorException(
            ResponseError(
                ResponseErrorCode.InvalidRequest,
                "principal lacks required scope(s) for '$method': ${required.sorted()}",
                null,
            ),
        )

    private fun forbiddenPrincipalEnvelope(
        principal: PrincipalContext,
        toolName: String,
        required: Set<String>,
    ): ToolsCallResult {
        val ex = ForbiddenPrincipalException(
            principalId = principal.principalId,
            reason = "missing scope(s): ${required.sorted()}",
        )
        val base = errorMapper.map(ex)
        val withTool = base.copy(
            details = base.details + dev.dmigrate.server.core.error.ToolErrorDetail("toolName", toolName),
        )
        return errorEnvelopeResult(withTool)
    }

    private fun toWireTemplate(descriptor: ResourceTemplateDescriptor): ResourceTemplate =
        ResourceTemplate(
            uriTemplate = descriptor.uriTemplate,
            name = descriptor.name,
            mimeType = descriptor.mimeType,
            description = descriptor.description,
        )

    private fun dispatch(
        handler: dev.dmigrate.mcp.registry.ToolHandler,
        context: ToolCallContext,
    ): ToolsCallResult = try {
        // AP 6.19: bracket the handler with the request- and
        // response-byte enforcer when one is wired (Phase-C bootstrap
        // does, Phase-B tests don't). Both checks throw or rewrite
        // INSIDE the try, so the existing error mapper still owns the
        // envelope shape for any limit breach.
        responseLimitEnforcer?.enforceRequestSize(context.name, context.arguments)
        val rawOutcome = handler.handle(context)
        val outcome = responseLimitEnforcer
            ?.enforceResponseSize(context.name, context.principal, rawOutcome)
            ?: rawOutcome
        when (outcome) {
            is ToolCallOutcome.Success -> ToolsCallResult(
                content = outcome.content.map(::toWireContent),
                isError = false,
            )
            is ToolCallOutcome.Error -> errorEnvelopeResult(outcome.envelope)
        }
    } catch (e: Throwable) {
        // §12.8: known-but-unimplemented tools raise
        // UnsupportedToolOperationException; other ApplicationExceptions
        // surface their own envelopes; anything else lands as
        // INTERNAL_AGENT_ERROR via the default mapper.
        errorEnvelopeResult(errorMapper.map(e))
    }

    private fun toMetadata(descriptor: ToolDescriptor): ToolMetadata = ToolMetadata(
        name = descriptor.name,
        title = descriptor.title,
        description = descriptor.description,
        inputSchema = descriptor.inputSchema,
        outputSchema = descriptor.outputSchema,
        requiredScopes = descriptor.requiredScopes.sorted(),
    )

    private fun toWireContent(content: ToolContent): ToolsCallContent = ToolsCallContent(
        type = content.type,
        text = content.text,
        data = content.data,
        mimeType = content.mimeType,
    )

    /**
     * Serializes an envelope to the §12.16 wire shape:
     * `{"code", "message", "details": [{"key", "value"}, ...], "requestId"?}`.
     *
     * `details` is a JSON ARRAY of `{key, value}` objects, not an
     * object — `ValidationErrorException` legitimately emits multiple
     * details with the same `key` (one per violation on a field), and
     * an object-shaped `details` would silently drop duplicates.
     * `requestId` is omitted when null so clients don't see a
     * literal `null` field.
     */
    private fun errorEnvelopeResult(
        envelope: dev.dmigrate.server.core.error.ToolErrorEnvelope,
    ): ToolsCallResult {
        val payload = buildMap<String, Any> {
            put("code", envelope.code.name)
            put("message", envelope.message)
            put("details", envelope.details.map { mapOf("key" to it.key, "value" to it.value) })
            envelope.requestId?.let { put("requestId", it) }
        }
        return ToolsCallResult(
            content = listOf(
                ToolsCallContent(
                    type = "text",
                    text = gson.toJson(payload),
                    mimeType = "application/json",
                ),
            ),
            isError = true,
        )
    }

    private fun failedWithMethodNotFound(message: String): CompletableFuture<ToolsCallResult> =
        CompletableFuture.failedFuture(
            ResponseErrorException(
                ResponseError(ResponseErrorCode.MethodNotFound, message, null),
            ),
        )

    /**
     * §4.2 + §12.15: a missing principal at the stdio layer is
     * surfaced as `AUTH_REQUIRED` on the `tools/call` envelope (NOT a
     * JSON-RPC error). Routes that cannot supply a principal (e.g.
     * stdio without a configured token registry, or HTTP before the
     * session is bound) wire [principalProvider] to return `null`;
     * the resulting envelope tells the client "you must authenticate".
     *
     * The tool name is carried in structured `details` (parallel to
     * `UnsupportedToolOperationException`'s `toolName` detail) — never
     * in the free-form `message` — so clients can correlate without
     * parsing.
     */
    private fun failedAuthRequiredEnvelope(toolName: String): CompletableFuture<ToolsCallResult> {
        val base = errorMapper.map(AuthRequiredException())
        val withTool = base.copy(
            details = base.details + dev.dmigrate.server.core.error.ToolErrorDetail("toolName", toolName),
        )
        return CompletableFuture.completedFuture(errorEnvelopeResult(withTool))
    }
}
