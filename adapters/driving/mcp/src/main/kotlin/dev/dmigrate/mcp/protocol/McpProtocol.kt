package dev.dmigrate.mcp.protocol

/**
 * Protocol contract pinned by MCP 2025-11-25 and ImpPlan-0.9.6-B.md §5.3.
 * The MCP `protocolVersion` is what gets negotiated on `initialize`;
 * the d-migrate contract version (`v1`) is exposed only in product
 * capabilities or `capabilities_list` — never as the MCP protocolVersion
 * (§5.3 verbot).
 */
object McpProtocol {
    const val MCP_PROTOCOL_VERSION: String = "2025-11-25"
    const val DMIGRATE_CONTRACT_VERSION: String = "v1"
    const val SERVER_NAME: String = "d-migrate"
}
