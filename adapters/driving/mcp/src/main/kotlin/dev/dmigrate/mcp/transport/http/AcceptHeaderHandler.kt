package dev.dmigrate.mcp.transport.http

/**
 * `Accept`-Header parser per `ImpPlan-0.9.6-B.md` §12.13.
 *
 * Streamable HTTP requires POST callers to advertise both response
 * shapes from MCP 2025-11-25: `application/json` and
 * `text/event-stream`. The server still answers JSON-only today, but
 * the Accept contract is strict so remote clients negotiate the
 * standard transport surface rather than a d-migrate-specific subset.
 */
internal object AcceptHeaderHandler {

    /** Returns true when the request advertises both streamable shapes. */
    fun acceptsJson(acceptHeader: String?): Boolean {
        if (acceptHeader.isNullOrBlank()) return false
        val mediaTypes = acceptHeader.split(',').map { it.substringBefore(';').trim().lowercase() }
        return "application/json" in mediaTypes && "text/event-stream" in mediaTypes
    }
}
