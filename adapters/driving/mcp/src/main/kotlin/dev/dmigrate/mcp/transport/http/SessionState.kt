package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.protocol.McpService
import org.eclipse.lsp4j.jsonrpc.services.GenericEndpoint
import java.time.Instant

/**
 * Per-session HTTP state per `ImpPlan-0.9.6-B.md` §12.5 / §12.13.
 *
 * Phase B holds the negotiated protocol version, lifecycle timestamps,
 * and the [McpService] instance that owns the post-Initialize handler
 * graph (so follow-up requests dispatch to the same negotiated state).
 * `principalContext` joins the record in AP 6.6 once HTTP-Auth lands.
 *
 * [endpoint] is built lazily on first use. lsp4j's `GenericEndpoint`
 * scans `@JsonRequest`/`@JsonNotification` annotations in its
 * constructor — caching it once per session avoids a reflection scan
 * on every follow-up request.
 */
class SessionState(
    val negotiatedProtocolVersion: String,
    val createdAt: Instant,
    @Volatile var lastSeen: Instant,
    val service: McpService,
) {
    val endpoint: GenericEndpoint by lazy { GenericEndpoint(service) }
}
