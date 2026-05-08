package dev.dmigrate.mcp.registry

import dev.dmigrate.server.core.error.ToolErrorCode

/**
 * Transport-neutral metadata for a single MCP tool per
 * `ImpPlan-0.9.6-B.md` §4.7 + §6.8.
 *
 * The descriptor is stable across stdio and HTTP — the same instance
 * is read by both transports' `tools/list`. It does NOT carry the
 * handler reference; the [ToolRegistry] keeps the handler/descriptor
 * association so the descriptor stays a pure value type that's safe to
 * serialize on `tools/list`.
 *
 * @param name JSON-RPC tool name (e.g. `"capabilities_list"`,
 *  `"schema_validate"`). Stable contract — clients invoke this via
 *  `tools/call name=<name>`.
 * @param title human-readable label for client UIs (MCP optional).
 * @param description short description; ends up in `tools/list`.
 * @param requiredScopes scope set the principal must hold to invoke
 *  this tool. Mirrors `McpServerConfig.scopeMapping[name]` (§12.9) —
 *  the registry pins it on the descriptor so `capabilities_list` can
 *  project the scope table without re-reading the config.
 * @param inputSchema MCP `inputSchema` per JSON Schema 2020-12 (§5.6).
 *  AP 6.8 ships minimal stubs; AP 6.10 swaps in the real schemas with
 *  the golden-test contract.
 * @param outputSchema MCP `outputSchema` (optional in MCP spec, but
 *  Phase B treats it as required for stable client tooling).
 * @param inlineLimits free-form hints about size limits — e.g.
 *  "max 1 MiB result, larger payloads via `artifact_chunk_get`".
 *  Surfaced through `capabilities_list` only (not on `tools/list`).
 * @param resourceFallbackHint pointer to a resource template clients
 *  should use when the inline result would exceed [inlineLimits].
 *  Same projection rules as [inlineLimits].
 * @param errorCodes the [ToolErrorCode]s a successful dispatch may
 *  emit on the result envelope. Helps clients build retry/retry-not
 *  logic without parsing free-form messages.
 */
data class ToolDescriptor(
    val name: String,
    val title: String,
    val description: String,
    val requiredScopes: Set<String>,
    val inputSchema: Map<String, Any>,
    val outputSchema: Map<String, Any>,
    val inlineLimits: String? = null,
    val resourceFallbackHint: String? = null,
    val errorCodes: Set<ToolErrorCode> = emptySet(),
)
