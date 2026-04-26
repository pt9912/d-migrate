package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.protocol.McpService
import dev.dmigrate.mcp.transport.McpEndpointFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.jsonrpc.MessageConsumer
import org.eclipse.lsp4j.jsonrpc.messages.Message
import org.eclipse.lsp4j.jsonrpc.messages.NotificationMessage
import org.eclipse.lsp4j.jsonrpc.messages.RequestMessage
import org.eclipse.lsp4j.jsonrpc.messages.ResponseError
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.eclipse.lsp4j.jsonrpc.messages.ResponseMessage
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Minimal `POST /mcp` endpoint per AP 6.4.
 *
 * Each POST is a single JSON-RPC dispatch:
 *
 * 1. Read the body as UTF-8 text.
 * 2. Parse via lsp4j's [MessageJsonHandler] (Gson).
 * 3. Hand to a per-request `RemoteEndpoint` whose outbound consumer
 *    captures the response (single-shot).
 * 4. Serialize the captured response back into the HTTP body, or
 *    return `204 No Content` for notifications.
 *
 * Auth, `Origin` validation, `MCP-Session-Id` and `Accept`-header
 * handling all live in AP 6.5/6.6 — this route is intentionally
 * narrow so the AP-6.4 acceptance criterion ("MCP-Client kann ueber
 * HTTP initialisieren") can be verified in isolation.
 *
 * The [serviceFactory] supplies a fresh [McpService] per request so
 * `negotiatedProtocolVersion` state never leaks across HTTP calls.
 * AP 6.5 swaps this for a session-keyed lookup.
 */
fun Application.installMcpHttpRoute(serviceFactory: () -> McpService) {
    val jsonHandler = McpEndpointFactory.jsonHandler()
    routing {
        post("/mcp") {
            val body = call.receiveText()
            val message = try {
                jsonHandler.parseMessage(body)
            } catch (e: Exception) {
                LOG.warn("malformed POST /mcp body: {}", e.message)
                call.respond(HttpStatusCode.BadRequest, "malformed JSON-RPC")
                return@post
            }
            val capture = CaptureConsumer()
            val remote = McpEndpointFactory.remoteEndpoint(serviceFactory(), capture)
            try {
                remote.consume(message)
            } catch (e: Exception) {
                LOG.error("dispatch failed for POST /mcp", e)
                call.respond(HttpStatusCode.InternalServerError, e.message ?: "internal error")
                return@post
            }
            if (message is NotificationMessage) {
                call.respond(HttpStatusCode.NoContent)
                return@post
            }
            val response = try {
                // Non-blocking: suspend the coroutine instead of parking
                // the Ktor IO thread on `Future.get()`. dispatch is
                // typically immediate (initialize is synchronous), but
                // future tools may be async — withTimeout caps stuck
                // dispatches without holding a worker.
                withTimeout(DISPATCH_TIMEOUT_MS) { capture.future.await() }
            } catch (e: TimeoutCancellationException) {
                LOG.warn("dispatch timed out after {}ms: {}", DISPATCH_TIMEOUT_MS, e.message)
                val fallback = ResponseMessage().apply {
                    jsonrpc = "2.0"
                    id = (message as? RequestMessage)?.id
                    error = ResponseError(
                        ResponseErrorCode.InternalError,
                        "dispatch timeout",
                        null,
                    )
                }
                call.respondText(jsonHandler.serialize(fallback), ContentType.Application.Json)
                return@post
            } catch (e: Exception) {
                LOG.warn("dispatch failed: {}", e.message)
                val fallback = ResponseMessage().apply {
                    jsonrpc = "2.0"
                    id = (message as? RequestMessage)?.id
                    error = ResponseError(
                        ResponseErrorCode.InternalError,
                        e.message ?: "Internal error",
                        null,
                    )
                }
                call.respondText(jsonHandler.serialize(fallback), ContentType.Application.Json)
                return@post
            }
            call.respondText(jsonHandler.serialize(response), ContentType.Application.Json)
        }
    }
}

private class CaptureConsumer : MessageConsumer {
    val future: CompletableFuture<Message> = CompletableFuture()
    override fun consume(message: Message) {
        future.complete(message)
    }
}

private val LOG = LoggerFactory.getLogger("dev.dmigrate.mcp.transport.http.McpHttpRoute")
private const val DISPATCH_TIMEOUT_MS: Long = 10_000L
