package dev.dmigrate.mcp.transport.http

/**
 * Origin allowlist matcher per `ImpPlan-0.9.6-B.md` §12.6.
 *
 * Match semantics:
 * - exact origin: `http://app.example` matches itself, no other.
 * - port-suffix wildcard: `http://localhost:*` matches any TCP port
 *   on `http://localhost` (e.g., `:8080`, `:53000`) but not other
 *   hosts. The `*` MUST be the entire port — substrings or regex
 *   syntax are not supported.
 *
 * A `null` Origin (e.g., curl, file://, server-to-server) is allowed
 * because there is no browser cross-origin attack vector to block.
 * Browser-issued requests always carry Origin per Fetch spec.
 *
 * Wildcard `*` alone is rejected at config validation time (§12.12).
 */
internal object OriginValidator {

    fun isAllowed(origin: String?, allowedOrigins: Set<String>): Boolean {
        if (origin == null) return true
        return allowedOrigins.any { pattern -> matches(origin, pattern) }
    }

    private fun matches(origin: String, pattern: String): Boolean {
        if (pattern == origin) return true
        if (!pattern.endsWith(":*")) return false
        val prefix = pattern.substringBeforeLast(":*")
        if (!origin.startsWith("$prefix:")) return false
        val portPart = origin.substring(prefix.length + 1)
        return portPart.toIntOrNull()?.let { it in 0..MAX_PORT } == true
    }

    private const val MAX_PORT: Int = 65535
}
