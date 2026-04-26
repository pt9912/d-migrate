package dev.dmigrate.mcp.transport.http

/**
 * `WWW-Authenticate` Bearer-Challenge builder per RFC 6750 §3 and
 * `ImpPlan-0.9.6-B.md` §12.14.
 *
 * Three Phase-B shapes:
 *
 * | Status | Reason                              | Output                                                                                                  |
 * |--------|-------------------------------------|---------------------------------------------------------------------------------------------------------|
 * | `401`  | no token                            | `Bearer realm="dmigrate-mcp", resource_metadata="<url>"`                                                |
 * | `401`  | invalid_token (sig/exp/aud/iss/...) | `Bearer realm="dmigrate-mcp", error="invalid_token", error_description="<text>", resource_metadata=...` |
 * | `403`  | insufficient_scope                  | `Bearer realm="dmigrate-mcp", error="insufficient_scope", scope="...", resource_metadata=...`           |
 */
internal object ChallengeBuilder {

    private const val REALM = "dmigrate-mcp"

    fun missingToken(metadataUrl: String): String =
        """Bearer realm="$REALM", resource_metadata="${escape(metadataUrl)}""""

    fun invalidToken(reason: String, metadataUrl: String): String =
        """Bearer realm="$REALM", error="invalid_token", """ +
            """error_description="${escape(reason)}", """ +
            """resource_metadata="${escape(metadataUrl)}""""

    fun insufficientScope(required: Set<String>, metadataUrl: String): String =
        """Bearer realm="$REALM", error="insufficient_scope", """ +
            """scope="${escape(required.joinToString(" "))}", """ +
            """resource_metadata="${escape(metadataUrl)}""""

    /**
     * RFC 7235 quoted-pair / qdtext: backslash and double-quote MUST
     * be escaped; control chars are forbidden but we drop them
     * defensively.
     */
    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + ESCAPE_PADDING)
        for (c in value) {
            when {
                c == '\\' || c == '"' -> sb.append('\\').append(c)
                c.code < CONTROL_CHAR_LIMIT || c.code == DEL_CHAR -> Unit
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private const val ESCAPE_PADDING: Int = 8
    private const val CONTROL_CHAR_LIMIT: Int = 0x20
    private const val DEL_CHAR: Int = 0x7F
}
