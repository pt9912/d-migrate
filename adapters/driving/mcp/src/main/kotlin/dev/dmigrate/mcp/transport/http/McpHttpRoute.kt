package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.auth.AuthValidator
import dev.dmigrate.mcp.auth.BearerTokenReader
import dev.dmigrate.mcp.auth.BearerValidationResult
import dev.dmigrate.mcp.auth.DisabledAuthValidator
import dev.dmigrate.mcp.auth.IntrospectionAuthValidator
import dev.dmigrate.mcp.auth.JwksAuthValidator
import dev.dmigrate.mcp.auth.ScopeChecker
import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.protocol.McpService
import dev.dmigrate.mcp.server.AuthMode
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.transport.McpEndpointFactory
import dev.dmigrate.server.core.principal.PrincipalContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.host
import io.ktor.server.request.port
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.jsonrpc.Endpoint
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.eclipse.lsp4j.jsonrpc.services.GenericEndpoint
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * Streamable-HTTP route per `ImpPlan-0.9.6-B.md` §6.5 + §6.6 +
 * §12.13 + §12.14.
 *
 * Endpoints:
 * - `POST /mcp` — JSON-RPC dispatch with Origin / Accept / body /
 *   Bearer / Session / Protocol-Version / Scope chain.
 * - `GET /mcp` — `405 Method Not Allowed` (no SSE in Phase B)
 * - `DELETE /mcp` — terminates the session (`200` with valid id,
 *   `405` without)
 * - `GET /.well-known/oauth-protected-resource` — RFC 9728 metadata
 *
 * Validation order (§12.14):
 *
 * 1. Origin → 403 if not in allowlist
 * 2. Accept → 406 if SSE-only
 * 3. Body parse → 400 -32600 / -32700
 * 4. JSON-RPC parse (method + id known)
 * 5. Bearer-validation → 401 (skipped for `AuthMode.DISABLED`)
 * 6. Method-aware Session/Protocol headers (initialize is exempt)
 * 7. Scope-check (initialize / notifications/initialized are exempt)
 * 8. Dispatch (lsp4j RemoteEndpoint)
 */
fun Application.installMcpHttpRoute(
    config: McpServerConfig,
    serviceFactory: () -> McpService,
    authValidatorOverride: AuthValidator? = null,
) {
    val jsonHandler = McpEndpointFactory.jsonHandler()
    val sessionManager = SessionManager(idleTimeout = config.sessionIdleTimeout)
    val authValidator = authValidatorOverride ?: createAuthValidator(config)

    monitor.subscribe(ApplicationStopping) {
        sessionManager.close()
        if (authValidator is AutoCloseable) authValidator.close()
    }

    routing {
        post("/mcp") {
            handleMcpPost(call, config, jsonHandler, sessionManager, authValidator, serviceFactory)
        }
        get("/mcp") {
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
        delete("/mcp") {
            handleMcpDelete(call, sessionManager)
        }
        get(METADATA_PATH) {
            call.respondText(
                text = ProtectedResourceMetadata.render(config, resolveResourceUri(call, config)),
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.OK,
            )
        }
    }
}

private fun createAuthValidator(config: McpServerConfig): AuthValidator = when (config.authMode) {
    AuthMode.DISABLED -> DisabledAuthValidator()
    AuthMode.JWT_JWKS -> JwksAuthValidator(config)
    AuthMode.JWT_INTROSPECTION -> IntrospectionAuthValidator(config)
}

/**
 * Resolved per-request handler: the local [service] (used to register
 * the session on Initialize success), the cached lsp4j [endpoint],
 * and the validated [principal] from the Bearer step. The principal
 * is the single source of truth for scope decisions on this request.
 */
private class ServiceContext(
    val service: McpService,
    val endpoint: Endpoint,
    val principal: PrincipalContext,
)

@Suppress("LongParameterList")
private suspend fun handleMcpPost(
    call: ApplicationCall,
    config: McpServerConfig,
    jsonHandler: MessageJsonHandler,
    sessionManager: SessionManager,
    authValidator: AuthValidator,
    serviceFactory: () -> McpService,
) {
    if (!checkOrigin(call, config)) return
    if (!checkAccept(call, jsonHandler)) return
    val message = parseBody(call, jsonHandler) ?: return
    val isInitialize = message is RequestMessage && message.method == METHOD_INITIALIZE
    val method = methodOf(message)
    val principal = validateBearer(call, config, authValidator) ?: return
    val context = resolveContext(
        call, jsonHandler, sessionManager, serviceFactory, principal, message, isInitialize,
    ) ?: return
    if (!checkScopes(call, config, method, context.principal)) return
    dispatchAndRespond(call, jsonHandler, sessionManager, context, message, isInitialize)
}

private fun methodOf(message: Message): String? = when (message) {
    is RequestMessage -> message.method
    is NotificationMessage -> message.method
    else -> null
}

private suspend fun checkOrigin(call: ApplicationCall, config: McpServerConfig): Boolean {
    val origin = call.request.header(HttpHeaders.Origin)
    if (OriginValidator.isAllowed(origin, config.allowedOrigins)) return true
    call.respondText(
        text = "origin '${origin ?: "<absent>"}' is not in the allowlist",
        contentType = ContentType.Text.Plain,
        status = HttpStatusCode.Forbidden,
    )
    return false
}

private suspend fun checkAccept(call: ApplicationCall, jsonHandler: MessageJsonHandler): Boolean {
    val accept = call.request.header(HttpHeaders.Accept)
    if (AcceptHeaderHandler.acceptsJson(accept)) return true
    respondJsonRpcError(
        call, jsonHandler,
        HttpStatusCode.NotAcceptable,
        id = null,
        code = ResponseErrorCode.InvalidRequest.value,
        message = "Phase B answers application/json only; SSE is not implemented",
    )
    return false
}

private suspend fun parseBody(call: ApplicationCall, jsonHandler: MessageJsonHandler): Message? {
    val body = call.receiveText()
    if (body.isBlank()) {
        respondJsonRpcError(
            call, jsonHandler,
            HttpStatusCode.BadRequest,
            id = null,
            code = ResponseErrorCode.InvalidRequest.value,
            message = "empty body",
        )
        return null
    }
    return try {
        jsonHandler.parseMessage(body)
    } catch (e: Exception) {
        respondJsonRpcError(
            call, jsonHandler,
            HttpStatusCode.BadRequest,
            id = null,
            code = ResponseErrorCode.ParseError.value,
            message = "Parse error: ${e.message ?: e.javaClass.simpleName}",
        )
        null
    }
}

private suspend fun validateBearer(
    call: ApplicationCall,
    config: McpServerConfig,
    authValidator: AuthValidator,
): PrincipalContext? {
    if (config.authMode == AuthMode.DISABLED) return DisabledAuthValidator.ANONYMOUS_PRINCIPAL
    val token = BearerTokenReader.read(call.request.header(HttpHeaders.Authorization))
    if (token == null) {
        respondAuthChallenge(
            call, HttpStatusCode.Unauthorized,
            ChallengeBuilder.missingToken(resolveMetadataUrl(call, config)),
        )
        return null
    }
    return when (val result = authValidator.validate(token)) {
        is BearerValidationResult.Valid -> result.principal
        is BearerValidationResult.Invalid -> {
            respondAuthChallenge(
                call, HttpStatusCode.Unauthorized,
                ChallengeBuilder.invalidToken(result.reason, resolveMetadataUrl(call, config)),
            )
            null
        }
    }
}

@Suppress("LongParameterList")
private suspend fun resolveContext(
    call: ApplicationCall,
    jsonHandler: MessageJsonHandler,
    sessionManager: SessionManager,
    serviceFactory: () -> McpService,
    principal: PrincipalContext,
    message: Message,
    isInitialize: Boolean,
): ServiceContext? {
    if (isInitialize) {
        val service = serviceFactory()
        return ServiceContext(service, GenericEndpoint(service), principal)
    }
    val sessionId = parseSessionIdHeader(call)
    val state = sessionId?.let { sessionManager.peek(it) } ?: run {
        respondJsonRpcError(
            call, jsonHandler,
            HttpStatusCode.NotFound,
            id = (message as? RequestMessage)?.id,
            code = JSONRPC_ERROR_SESSION_UNKNOWN,
            message = "session expired or unknown",
        )
        return null
    }
    val versionHeader = call.request.header(HEADER_MCP_PROTOCOL_VERSION)
    if (versionHeader != state.negotiatedProtocolVersion) {
        respondJsonRpcError(
            call, jsonHandler,
            HttpStatusCode.BadRequest,
            id = (message as? RequestMessage)?.id,
            code = JSONRPC_ERROR_PROTOCOL_VERSION_MISMATCH,
            message = "MCP-Protocol-Version mismatch (expected " +
                "${state.negotiatedProtocolVersion}, got ${versionHeader ?: "<absent>"})",
        )
        return null
    }
    sessionManager.touch(sessionId)
    return ServiceContext(state.service, state.endpoint, principal)
}

private suspend fun checkScopes(
    call: ApplicationCall,
    config: McpServerConfig,
    method: String?,
    principal: PrincipalContext,
): Boolean {
    if (config.authMode == AuthMode.DISABLED) return true
    if (method == null) return true
    if (ScopeChecker.isScopeFree(method)) return true
    val required = ScopeChecker.requiredScopes(method, config.scopeMapping)
    if (ScopeChecker.isSatisfied(principal.scopes, required)) return true
    respondAuthChallenge(
        call, HttpStatusCode.Forbidden,
        ChallengeBuilder.insufficientScope(required, resolveMetadataUrl(call, config)),
    )
    return false
}

@Suppress("LongParameterList")
private suspend fun dispatchAndRespond(
    call: ApplicationCall,
    jsonHandler: MessageJsonHandler,
    sessionManager: SessionManager,
    context: ServiceContext,
    message: Message,
    isInitialize: Boolean,
) {
    val capture = CaptureConsumer()
    val remote = McpEndpointFactory.remoteEndpoint(context.endpoint, capture)
    try {
        remote.consume(message)
    } catch (e: Exception) {
        LOG.error("dispatch failed", e)
        respondJsonRpcError(
            call, jsonHandler,
            HttpStatusCode.InternalServerError,
            id = (message as? RequestMessage)?.id,
            code = ResponseErrorCode.InternalError.value,
            message = e.message ?: "internal error",
        )
        return
    }

    if (message is NotificationMessage || message is ResponseMessage) {
        call.respond(HttpStatusCode.Accepted)
        return
    }

    val response = awaitResponse(capture, message)
    if (isInitialize && response.error == null) {
        registerSessionAfterInitialize(call, sessionManager, context.service, context.principal)
    }
    call.respondText(jsonHandler.serialize(response), ContentType.Application.Json)
}

private suspend fun awaitResponse(capture: CaptureConsumer, message: Message): ResponseMessage {
    val raw = try {
        withTimeout(DISPATCH_TIMEOUT_MS) { capture.future.await() }
    } catch (e: TimeoutCancellationException) {
        LOG.warn("dispatch timed out after {}ms: {}", DISPATCH_TIMEOUT_MS, e.message)
        return buildJsonRpcErrorMessage(
            id = (message as? RequestMessage)?.id,
            code = ResponseErrorCode.InternalError.value,
            message = "dispatch timeout",
        )
    } catch (e: Exception) {
        LOG.warn("dispatch failed: {}", e.message)
        return buildJsonRpcErrorMessage(
            id = (message as? RequestMessage)?.id,
            code = ResponseErrorCode.InternalError.value,
            message = e.message ?: "Internal error",
        )
    }
    return raw as? ResponseMessage ?: buildJsonRpcErrorMessage(
        id = (message as? RequestMessage)?.id,
        code = ResponseErrorCode.InternalError.value,
        message = "unexpected dispatch result type: ${raw::class.simpleName}",
    )
}

private fun registerSessionAfterInitialize(
    call: ApplicationCall,
    sessionManager: SessionManager,
    service: McpService,
    principal: PrincipalContext,
) {
    val now = Instant.now()
    val state = SessionState(
        negotiatedProtocolVersion = McpProtocol.MCP_PROTOCOL_VERSION,
        createdAt = now,
        lastSeen = now,
        service = service,
        principalContext = principal,
    )
    val sessionId = sessionManager.create(state)
    call.response.headers.append(HEADER_MCP_SESSION_ID, sessionId.toString())
    call.response.headers.append(HEADER_MCP_PROTOCOL_VERSION, McpProtocol.MCP_PROTOCOL_VERSION)
}

private suspend fun handleMcpDelete(call: ApplicationCall, sessionManager: SessionManager) {
    val sessionId = parseSessionIdHeader(call)
    if (sessionId == null || !sessionManager.remove(sessionId)) {
        call.respond(HttpStatusCode.MethodNotAllowed)
        return
    }
    call.respond(HttpStatusCode.OK)
}

private fun parseSessionIdHeader(call: ApplicationCall): UUID? {
    val raw = call.request.header(HEADER_MCP_SESSION_ID) ?: return null
    return runCatching { UUID.fromString(raw) }.getOrNull()
}

private suspend fun respondAuthChallenge(
    call: ApplicationCall,
    status: HttpStatusCode,
    challenge: String,
) {
    call.response.headers.append(HttpHeaders.WWWAuthenticate, challenge)
    call.respondText(text = "", contentType = ContentType.Text.Plain, status = status)
}

private fun resolveMetadataUrl(call: ApplicationCall, config: McpServerConfig): String =
    "${resolveBaseUrl(call, config)}$METADATA_PATH"

private fun resolveResourceUri(call: ApplicationCall, config: McpServerConfig): String =
    "${resolveBaseUrl(call, config)}/mcp"

/**
 * Resolves the base URL used to build resource_metadata pointers and
 * the canonical MCP resource URI. Honours `publicBaseUrl` first; the
 * fallback derives the scheme from the actual request via
 * `request.origin` so an HTTPS request — even without `publicBaseUrl`
 * configured — never advertises an `http://` metadata URL (avoids a
 * silent downgrade in `WWW-Authenticate` challenges).
 */
private fun resolveBaseUrl(call: ApplicationCall, config: McpServerConfig): String {
    config.publicBaseUrl?.let { return it.toString().trimEnd('/') }
    val origin = call.request.origin
    return "${origin.scheme}://${call.request.host()}:${call.request.port()}"
}

private suspend fun respondJsonRpcError(
    call: ApplicationCall,
    @Suppress("unused") jsonHandler: MessageJsonHandler,
    httpStatus: HttpStatusCode,
    id: String?,
    code: Int,
    message: String,
) {
    // Build the error envelope as raw JSON: lsp4j's MessageTypeAdapter
    // can throw on a ResponseMessage with no method context, which is
    // exactly the case for parse-error / empty-body shortcuts where we
    // emit a response BEFORE the dispatch chain runs.
    call.respondText(
        text = buildJsonRpcErrorJson(id, code, message),
        contentType = ContentType.Application.Json,
        status = httpStatus,
    )
}

private fun buildJsonRpcErrorMessage(id: String?, code: Int, message: String): ResponseMessage =
    ResponseMessage().apply {
        jsonrpc = "2.0"
        this.id = id
        error = ResponseError(code, message, null)
    }

private fun buildJsonRpcErrorJson(id: String?, code: Int, message: String): String {
    val idJson = if (id == null) "null" else "\"${JsonStringEscaper.escape(id)}\""
    return """{"jsonrpc":"2.0","id":$idJson,"error":{"code":$code,"message":"${JsonStringEscaper.escape(message)}"}}"""
}

private class CaptureConsumer : MessageConsumer {
    val future: CompletableFuture<Message> = CompletableFuture()
    override fun consume(message: Message) {
        future.complete(message)
    }
}

private val LOG = LoggerFactory.getLogger("dev.dmigrate.mcp.transport.http.McpHttpRoute")
private const val DISPATCH_TIMEOUT_MS: Long = 10_000L
private const val METHOD_INITIALIZE: String = "initialize"
private const val HEADER_MCP_SESSION_ID: String = "MCP-Session-Id"
private const val HEADER_MCP_PROTOCOL_VERSION: String = "MCP-Protocol-Version"
private const val JSONRPC_ERROR_SESSION_UNKNOWN: Int = -32_000
private const val JSONRPC_ERROR_PROTOCOL_VERSION_MISMATCH: Int = -32_001
private const val METADATA_PATH: String = "/.well-known/oauth-protected-resource"
