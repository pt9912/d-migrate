package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.protocol.McpProtocol
import dev.dmigrate.mcp.protocol.McpService
import dev.dmigrate.mcp.server.McpServerConfig
import dev.dmigrate.mcp.transport.McpEndpointFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.request.header
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
 * Streamable-HTTP route per `ImpPlan-0.9.6-B.md` §6.5 + §12.13.
 *
 * Endpoints:
 * - `POST /mcp` — JSON-RPC dispatch with full validation chain
 *   (Origin → Accept → body → JSON-RPC → headers → service)
 * - `GET /mcp` — `405 Method Not Allowed` (no SSE in Phase B)
 * - `DELETE /mcp` — terminates the session keyed by `MCP-Session-Id`
 *   header (returns `200`); `405` without a known id
 *
 * Initialize is the special case: the request carries no
 * `MCP-Session-Id` / `MCP-Protocol-Version` headers, the server
 * issues both in the response after a successful dispatch and stashes
 * the new session in [SessionManager]. Follow-up requests look up
 * the session by id and enforce the negotiated protocol version.
 *
 * AP 6.6 will layer Bearer / JWKS auth in front of the dispatch chain;
 * §12.13 fixes the validation order so the auth step slots in cleanly.
 */
fun Application.installMcpHttpRoute(
    config: McpServerConfig,
    serviceFactory: () -> McpService,
) {
    val jsonHandler = McpEndpointFactory.jsonHandler()
    val sessionManager = SessionManager(idleTimeout = config.sessionIdleTimeout)

    monitor.subscribe(ApplicationStopping) { sessionManager.close() }

    routing {
        post("/mcp") {
            handleMcpPost(call, config, jsonHandler, sessionManager, serviceFactory)
        }
        get("/mcp") {
            call.respond(HttpStatusCode.MethodNotAllowed)
        }
        delete("/mcp") {
            handleMcpDelete(call, sessionManager)
        }
    }
}

/**
 * Resolved per-request handler: the local [service] (used to register
 * the session on Initialize success) and the lsp4j [endpoint] used
 * for the actual dispatch. Follow-up requests reuse the
 * [SessionState.endpoint] cache; Initialize builds a fresh one.
 */
private class ServiceContext(val service: McpService, val endpoint: Endpoint)

private suspend fun handleMcpPost(
    call: ApplicationCall,
    config: McpServerConfig,
    jsonHandler: MessageJsonHandler,
    sessionManager: SessionManager,
    serviceFactory: () -> McpService,
) {
    if (!checkOrigin(call, config)) return
    if (!checkAccept(call, jsonHandler)) return
    val message = parseBody(call, jsonHandler) ?: return
    val isInitialize = message is RequestMessage && message.method == METHOD_INITIALIZE
    val context = resolveContext(
        call, jsonHandler, sessionManager, serviceFactory, message, isInitialize,
    ) ?: return
    dispatchAndRespond(call, jsonHandler, sessionManager, context, message, isInitialize)
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

private suspend fun resolveContext(
    call: ApplicationCall,
    jsonHandler: MessageJsonHandler,
    sessionManager: SessionManager,
    serviceFactory: () -> McpService,
    message: Message,
    isInitialize: Boolean,
): ServiceContext? {
    if (isInitialize) {
        val service = serviceFactory()
        return ServiceContext(service, GenericEndpoint(service))
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
    // Headers passed → request is accepted, refresh idle TTL.
    sessionManager.touch(sessionId)
    return ServiceContext(state.service, state.endpoint)
}

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
        registerSessionAfterInitialize(call, sessionManager, context.service)
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
) {
    val now = Instant.now()
    val state = SessionState(
        negotiatedProtocolVersion = McpProtocol.MCP_PROTOCOL_VERSION,
        createdAt = now,
        lastSeen = now,
        service = service,
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
    val idJson = if (id == null) "null" else "\"${escapeJson(id)}\""
    return """{"jsonrpc":"2.0","id":$idJson,"error":{"code":$code,"message":"${escapeJson(message)}"}}"""
}

/**
 * Conservative JSON-string escape covering the full RFC 8259
 * mandatory set: `\`, `"`, named controls (`\b` `\f` `\n` `\r` `\t`),
 * and any other code point below `U+0020` as `\\u%04x`. Used only for
 * raw error responses; the lsp4j Gson serializer handles dispatched
 * payloads.
 */
private fun escapeJson(value: String): String {
    val sb = StringBuilder(value.length + ESCAPE_PADDING)
    for (c in value) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < CONTROL_CHAR_LIMIT) {
                sb.append("\\u%04x".format(c.code))
            } else {
                sb.append(c)
            }
        }
    }
    return sb.toString()
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
private const val ESCAPE_PADDING: Int = 8
private const val CONTROL_CHAR_LIMIT: Int = 0x20
