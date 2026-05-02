package dev.dmigrate.mcp.registry

import com.google.gson.JsonElement
import dev.dmigrate.server.core.principal.PrincipalContext
import java.util.UUID

/**
 * Per-call dispatch context per `ImpPlan-0.9.6-B.md` §6.8 + §12.8.
 *
 * The principal is the validated `PrincipalContext` from §12.14
 * (HTTP-Bearer) or §12.15 (stdio-Token). Tool handlers MUST trust this
 * field for tenant/scope decisions — never read raw env or transport
 * headers themselves.
 *
 * [arguments] is the raw JSON-RPC `arguments` object as parsed by
 * lsp4j (Gson tree). Handlers that take typed arguments deserialize
 * lazily from this tree; handlers that take none ignore it. `null` =
 * the client omitted the field entirely.
 *
 * [requestId] is a server-minted correlator (AP 6.20). `McpServiceImpl`
 * generates a fresh value per `tools/call` and threads it into both
 * the [dev.dmigrate.server.core.audit.AuditEvent] and (optionally)
 * the handler's response so an operator can trace one logical
 * request across audit log + tool wire payload. The default keeps
 * Phase-B test code compiling without a mandatory rewrite.
 */
data class ToolCallContext(
    val name: String,
    val arguments: JsonElement?,
    val principal: PrincipalContext,
    val requestId: String = "req-${UUID.randomUUID().toString().take(8)}",
)

/**
 * Outcome of a single `tools/call` dispatch.
 *
 * - [Success] is mapped to MCP `tools/call` `content` with
 *   `isError=false`.
 * - [Error] is mapped to MCP `tools/call` `content` with
 *   `isError=true` and a `ToolErrorEnvelope` projection (§12.8).
 *   Handlers signal errors by either throwing an
 *   `ApplicationException` (preferred — the `tools/call` route maps it
 *   automatically via `DefaultErrorMapper`) OR returning [Error]
 *   directly when they want a custom envelope (e.g. to inject extra
 *   `details`).
 */
sealed interface ToolCallOutcome {

    data class Success(val content: List<ToolContent>) : ToolCallOutcome

    data class Error(val envelope: dev.dmigrate.server.core.error.ToolErrorEnvelope) : ToolCallOutcome
}

/**
 * MCP `tools/call` content fragment per the 2025-11-25 spec. Phase B
 * only emits text; richer types (`image`, `resource`) follow in later
 * phases.
 */
data class ToolContent(
    val type: String,
    val text: String? = null,
    val data: Any? = null,
    val mimeType: String? = null,
)

/**
 * Tool handler interface per §3.1 ("Handler-Schnittstelle fuer
 * spaetere Phasen definieren"). Phase B's only real handler is
 * `capabilities_list`; every other 0.9.6 tool is wired to
 * [UnsupportedToolHandler] so unknown tools and known-but-unimplemented
 * ones produce different errors (§6.8 acceptance).
 *
 * Handlers may run blocking IO once Phase C/D ships them — the
 * `tools/call` dispatch path in `McpServiceImpl` runs them on a
 * non-IO thread; long-running work belongs to a separate executor.
 * For Phase B, handlers must finish synchronously without IO.
 */
fun interface ToolHandler {

    fun handle(context: ToolCallContext): ToolCallOutcome
}
