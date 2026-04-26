package dev.dmigrate.mcp.auth

/**
 * Strict `Authorization: Bearer <token>` parser per RFC 6750 §2.1
 * and `ImpPlan-0.9.6-B.md` §12.14 — Bearer tokens MUST NOT come from
 * query parameters, cookies, or request bodies.
 *
 * Returns null when the header is absent, blank, has a non-Bearer
 * scheme, or the token portion is empty / not a single token.
 */
internal object BearerTokenReader {

    fun read(authorizationHeader: String?): String? {
        if (authorizationHeader.isNullOrBlank()) return null
        val parts = authorizationHeader.trim().split(' ', limit = 2)
        if (parts.size != 2) return null
        val scheme = parts[0]
        val token = parts[1].trim()
        if (!scheme.equals("Bearer", ignoreCase = true)) return null
        if (token.isEmpty() || token.contains(' ')) return null
        return token
    }
}
