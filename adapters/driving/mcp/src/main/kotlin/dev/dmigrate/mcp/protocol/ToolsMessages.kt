package dev.dmigrate.mcp.protocol

import com.google.gson.JsonElement

/**
 * MCP `tools/list` request shape per the 2025-11-25 specification +
 * `ImpPlan-0.9.6-B.md` §6.8. `cursor` is optional pagination — Phase
 * B always returns the full list (no pagination needed for ~25
 * tools), so the cursor is accepted but not honored.
 */
data class ToolsListParams(
    val cursor: String? = null,
)

data class ToolsListResult(
    val tools: List<ToolMetadata>,
    val nextCursor: String? = null,
)

/**
 * MCP `tools/list` per-tool projection. The `inputSchema` field is
 * required by the MCP spec; `outputSchema` is optional but Phase B
 * advertises it for every tool so client tooling has stable typing.
 *
 * Phase B's [requiredScopes] field is a d-migrate extension — MCP
 * itself has no scope concept. Clients that don't recognise the field
 * simply ignore it.
 */
data class ToolMetadata(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: Map<String, Any>,
    val outputSchema: Map<String, Any>,
    val requiredScopes: List<String>,
)

/**
 * MCP `tools/call` request shape. `arguments` is left as a Gson tree
 * so each tool handler decides how to deserialize it (Phase B's
 * `capabilities_list` ignores it; later tools will project into typed
 * argument records).
 */
data class ToolsCallParams(
    val name: String,
    val arguments: JsonElement? = null,
)

/**
 * MCP `tools/call` response per the 2025-11-25 specification + §12.8.
 *
 * `isError = true` carries an application-layer fault; the
 * `ToolErrorEnvelope` projection lives in `content` as a JSON content
 * fragment (per §12.8 — auth and protocol errors are NOT modelled
 * here).
 */
data class ToolsCallResult(
    val content: List<ToolsCallContent>,
    val isError: Boolean = false,
)

data class ToolsCallContent(
    val type: String,
    val text: String? = null,
    val data: Any? = null,
    val mimeType: String? = null,
)
