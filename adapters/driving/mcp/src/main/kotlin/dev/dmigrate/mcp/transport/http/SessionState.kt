package dev.dmigrate.mcp.transport.http

import dev.dmigrate.mcp.protocol.McpService
import dev.dmigrate.server.core.principal.PrincipalContext
import org.eclipse.lsp4j.jsonrpc.services.GenericEndpoint
import java.time.Instant

/**
 * Per-session HTTP state per `ImpPlan-0.9.6-B.md` §12.5 / §12.13 / §12.14.
 *
 * Holds the negotiated protocol version, lifecycle timestamps, the
 * [McpService] instance, and the [PrincipalContext] from the
 * Initialize-time Bearer validation. The principal is an
 * audit/last-validation snapshot — every follow-up request
 * re-validates its own Authorization header (§12.14 "per-request
 * validation").
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
    val principalContext: PrincipalContext,
) {
    val endpoint: GenericEndpoint by lazy { GenericEndpoint(service) }
}
