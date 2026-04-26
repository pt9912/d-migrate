package dev.dmigrate.mcp.transport.http

/**
 * `Accept`-Header parser per `ImpPlan-0.9.6-B.md` §12.13.
 *
 * Phase B answers JSON only; SSE is not implemented. An `Accept`
 * header that mentions ONLY `text/event-stream` (no JSON fallback,
 * no wildcard media type) must be rejected with HTTP `406`. All
 * other shapes — absent, wildcard, `application/json`, or any
 * combination that includes one of those — are accepted.
 */
internal object AcceptHeaderHandler {

    /** Returns true when the request can be answered with JSON. */
    fun acceptsJson(acceptHeader: String?): Boolean {
        if (acceptHeader.isNullOrBlank()) return true
        val mediaTypes = acceptHeader.split(',').map { it.substringBefore(';').trim().lowercase() }
        return mediaTypes.any { it == "*/*" || it == "application/*" || it == "application/json" }
    }
}
