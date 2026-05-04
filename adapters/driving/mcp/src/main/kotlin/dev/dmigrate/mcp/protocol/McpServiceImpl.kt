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
import dev.dmigrate.server.application.audit.AuditContext
import dev.dmigrate.server.application.audit.AuditScope
import dev.dmigrate.server.application.audit.SecretScrubber
import dev.dmigrate.mcp.resources.ResourceStores
import dev.dmigrate.mcp.resources.ResourcesReadHandler
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
    private val auditScope: AuditScope? = null,
    private val requestIdProvider: () -> String = ::generateDispatchRequestId,
    /**
     * AP D7: builds the capabilities document for `resources/read
     * dmigrate://capabilities`. Defaults to an empty map — the
     * bootstrap supplies the real provider (the one
     * [dev.dmigrate.mcp.registry.CapabilitiesListReadOnlyHandler]
     * uses) so the two surfaces stay in lock-step. An empty map
     * means the URI exists but the document is unconfigured —
     * the handler treats that as `RESOURCE_NOT_FOUND` so a stale
     * deployment never returns a half-baked capabilities body.
     */
    capabilitiesProvider: () -> Map<String, Any?> = { emptyMap() },
    /**
     * AP D7 sub-commit 3: inline-vs-artifactRef byte cap (§5.2).
     * Defaults to a fresh [McpLimitsConfig], which carries the
     * Plan-D MAX_INLINE_RESOURCE_CONTENT_BYTES and
     * MAX_RESOURCE_READ_RESPONSE_BYTES constants. Production
     * bootstrap supplies the deployment's tuned config so a server
     * with raised tool-response caps uses the matching resource
     * caps.
     */
    limitsConfig: dev.dmigrate.mcp.server.McpLimitsConfig = dev.dmigrate.mcp.server.McpLimitsConfig(),
) : McpService {

    private val negotiated = AtomicReference<String?>(null)
    private val currentPrincipal = AtomicReference(initialPrincipal)
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val resourcesListHandler = ResourcesListHandler(resourceStores)
    private val resourcesReadHandler = ResourcesReadHandler(resourceStores, capabilitiesProvider, limitsConfig)

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
        // §6.8 / Phase-B: `unknown tool` is JSON-RPC `-32601`, NOT
        // a tool-result envelope. The audit scope only opens after we
        // know the call addresses a registered tool — Phase-A audit
        // semantics record tool invocations, not protocol-method
        // typos.
        val handler = toolRegistry.findHandler(params.name)
            ?: return failedWithMethodNotFound("unknown tool '${params.name}'")
        val requestId = requestIdProvider()
        val principal = currentPrincipal.get()
        val auditContext = AuditContext(
            requestId = requestId,
            toolName = params.name,
            tenantId = principal?.effectiveTenantId,
            principalId = principal?.principalId,
        )
        return CompletableFuture.completedFuture(
            runAudited(auditContext) {
                // Throws AuthRequiredException / ForbiddenPrincipalException
                // / handler-internal ApplicationExceptions; the around
                // wrapper records the matching FAILURE outcome.
                val resolvedPrincipal = principal ?: throw AuthRequiredException()
                scopeViolation(params.name, resolvedPrincipal)?.let { required ->
                    throw ForbiddenPrincipalException(
                        principalId = resolvedPrincipal.principalId,
                        reason = "missing scope(s): ${required.sorted()}",
                    )
                }
                val context = ToolCallContext(
                    name = params.name,
                    arguments = params.arguments,
                    principal = resolvedPrincipal,
                    requestId = requestId,
                )
                rawDispatch(handler, context)
            },
        )
    }

    /**
     * Runs [block] inside the [auditScope] when one is wired and
     * translates any thrown exception into the same `tools/call`
     * error envelope `dispatch` would have produced — but only AFTER
     * `auditScope.around` has captured the failure outcome. Without
     * an audit scope this collapses to the pre-AP-6.20 try/catch.
     */
    private fun runAudited(
        auditContext: AuditContext,
        block: () -> ToolsCallResult,
    ): ToolsCallResult {
        val scoped: () -> ToolsCallResult = {
            // The around-block discards the AuditFields lambda
            // parameter on purpose: Phase-C handlers don't yet
            // populate `payloadFingerprint` / `resourceRefs`. When a
            // future handler needs to surface artifact refs into the
            // audit event, plumb AuditFields through ToolCallContext
            // (see AuditScope.kt:33-46).
            if (auditScope != null) auditScope.around(auditContext) { block() } else block()
        }
        return try {
            scoped()
        } catch (e: Throwable) {
            renderError(e, auditContext.requestId, auditContext.toolName ?: "?")
        }
    }

    /**
     * Pre-AP-6.20 [dispatch] used to wrap handler invocation in its
     * own try/catch. AP 6.20 hoists the catch one level up so
     * [auditScope] sees the typed `ApplicationException` and records
     * the matching FAILURE outcome. `rawDispatch` therefore lets
     * exceptions propagate; the caller (`runAudited`) maps them.
     */
    private fun rawDispatch(
        handler: dev.dmigrate.mcp.registry.ToolHandler,
        context: ToolCallContext,
    ): ToolsCallResult {
        responseLimitEnforcer?.enforceRequestSize(context.name, context.arguments)
        val raw = handler.handle(context)
        val outcome = responseLimitEnforcer
            ?.enforceResponseSize(context.name, context.principal, raw)
            ?: raw
        return when (outcome) {
            is ToolCallOutcome.Success -> ToolsCallResult(
                content = outcome.content.map(::toWireContent),
                isError = false,
            )
            is ToolCallOutcome.Error -> errorEnvelopeResult(outcome.envelope)
        }
    }

    private fun renderError(e: Throwable, requestId: String, toolName: String): ToolsCallResult {
        val base = errorMapper.map(e)
        // Pre-AP-6.20 helpers attached `toolName` as a structured
        // detail only for the two early-failure paths (AUTH_REQUIRED,
        // FORBIDDEN_PRINCIPAL); other exceptions carried their own
        // details from the typed exception's projection. Preserve
        // that wire shape — a global toolName detail would be a
        // cross-cutting wire change beyond the scope of AP 6.20.
        val needsToolName = e is AuthRequiredException || e is ForbiddenPrincipalException
        val details = if (needsToolName) {
            base.details + dev.dmigrate.server.core.error.ToolErrorDetail("toolName", toolName)
        } else {
            base.details
        }
        val enriched = base.copy(
            requestId = base.requestId ?: requestId,
            details = details,
        )
        return errorEnvelopeResult(enriched)
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

    override fun resourcesRead(params: ReadResourceParams): CompletableFuture<ReadResourceResult> {
        // §12.9 + §12.14: resources/read requires dmigrate:read.
        // Stdio reaches the service directly; HTTP also re-checks
        // upstream — the service-layer enforce keeps both transports
        // honest from one place.
        enforceScope("resources/read")?.let { return CompletableFuture.failedFuture(it) }
        // enforceScope guarantees a non-null principal when it returns
        // null, but HTTP can rebind concurrently between the scope
        // check and the read; re-fetch and re-check defensively.
        val principal = currentPrincipal.get()
            ?: return CompletableFuture.failedFuture(
                ResponseErrorException(
                    ResponseError(ResponseErrorCode.InvalidRequest, "principal not bound", null),
                ),
            )
        // Plan-D §5.3 / §10.7: `uri` is the ONLY accepted field on
        // `resources/read`. Any other key (cursor, range, chunkId,
        // unknown extension) is captured by
        // [StrictReadResourceParamsAdapter] and surfaces here as a
        // typed VALIDATION_ERROR. Done before the missing-uri check
        // so a request like `{"chunkId":"x"}` reports "unknown
        // parameter" rather than "missing uri" — the surface a
        // probing client touches first matters for the no-oracle
        // contract.
        params.unknownParameter?.let { unknown ->
            return CompletableFuture.failedFuture(
                ResponseErrorException(
                    ResponseError(
                        ResponseErrorCode.InvalidParams.value,
                        "resources/read does not accept '$unknown'",
                        mapOf("dmigrateCode" to "VALIDATION_ERROR"),
                    ),
                ),
            )
        }
        val raw = params.uri ?: return CompletableFuture.failedFuture(
            ResponseErrorException(
                ResponseError(
                    ResponseErrorCode.InvalidParams.value,
                    "resources/read requires 'uri'",
                    // Phase-D §5.4: every resource error carries
                    // error.data.dmigrateCode. A missing 'uri' is
                    // a request-shape failure → VALIDATION_ERROR.
                    mapOf("dmigrateCode" to "VALIDATION_ERROR"),
                ),
            ),
        )
        return try {
            CompletableFuture.completedFuture(resourcesReadHandler.read(principal, raw))
        } catch (e: ResponseErrorException) {
            CompletableFuture.failedFuture(e)
        }
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
        return if (ScopeChecker.isSatisfied(principal.scopes, required, principal.isAdmin)) {
            null
        } else {
            required
        }
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

    private fun toWireTemplate(descriptor: ResourceTemplateDescriptor): ResourceTemplate =
        ResourceTemplate(
            uriTemplate = descriptor.uriTemplate,
            name = descriptor.name,
            mimeType = descriptor.mimeType,
            description = descriptor.description,
        )

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
        // AP 6.23: scrub the message + every details[].(key,value)
        // through SecretScrubber as a serialisation boundary.
        // Upstream mappers already scrub typed validation messages,
        // but generic / forwarded errors can still carry Bearer
        // tokens, JDBC URLs or local paths in their details — this
        // is the last hop before the wire payload is rendered. The
        // key is also scrubbed because ValidationViolation.field is
        // upstream-derived (e.g. dotted JSON paths into user-supplied
        // schemas) and could in principle carry the same kind of
        // accidental secret as the value (review W3).
        val payload = buildMap<String, Any> {
            put("code", envelope.code.name)
            put("message", SecretScrubber.scrub(envelope.message))
            put(
                "details",
                envelope.details.map {
                    mapOf(
                        "key" to SecretScrubber.scrub(it.key),
                        "value" to SecretScrubber.scrub(it.value),
                    )
                },
            )
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

    private companion object {
        fun generateDispatchRequestId(): String =
            "req-${java.util.UUID.randomUUID().toString().take(8)}"
    }
}
