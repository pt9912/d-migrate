package dev.dmigrate.mcp.protocol

/**
 * MCP `initialize` request and response shapes per the 2025-11-25
 * specification. Optional fields default to `null` so Gson omits them
 * on serialization — matches MCP's "absent" semantics for unset
 * capabilities.
 */
data class InitializeParams(
    val protocolVersion: String,
    val capabilities: ClientCapabilities = ClientCapabilities(),
    val clientInfo: ClientInfo = ClientInfo(name = "unknown", version = "unknown"),
)

data class ClientCapabilities(
    val experimental: Map<String, Any>? = null,
    val roots: Map<String, Any>? = null,
    val sampling: Map<String, Any>? = null,
)

data class ClientInfo(
    val name: String,
    val version: String,
)

data class InitializeResult(
    val protocolVersion: String,
    val capabilities: ServerCapabilities,
    val serverInfo: ServerInfo,
    val instructions: String? = null,
)

/**
 * §5.3: capabilities reflect only what is actually implemented.
 * AP 6.4 ships none — `tools` lights up in AP 6.8, `resources` in AP 6.9.
 * Subscribe/listChanged stay absent until subscriptions arrive (Phase C+).
 */
data class ServerCapabilities(
    val tools: Map<String, Any>? = null,
    val resources: Map<String, Any>? = null,
    val prompts: Map<String, Any>? = null,
    val experimental: Map<String, Any>? = null,
)

data class ServerInfo(
    val name: String,
    val version: String,
)
