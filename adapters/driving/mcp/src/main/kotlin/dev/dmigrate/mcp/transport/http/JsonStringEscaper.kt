package dev.dmigrate.mcp.transport.http

/**
 * RFC 8259-compliant JSON string-content escape used by the
 * hand-rolled JSON builders in the HTTP transport
 * ([ProtectedResourceMetadata], [McpHttpRoute]'s error envelope) —
 * lsp4j's Gson-backed serializer handles dispatched payloads.
 *
 * Escapes:
 * - `\` -> `\\`
 * - `"` -> `\"`
 * - `\b` (U+0008) -> `\b`
 * - `\f` (U+000C) -> `\f`
 * - `\n` (U+000A) -> `\n`
 * - `\r` (U+000D) -> `\r`
 * - `\t` (U+0009) -> `\t`
 * - any other code point below `U+0020` -> `\\u%04x`
 */
internal object JsonStringEscaper {

    private const val ESCAPE_PADDING: Int = 8
    private const val CONTROL_CHAR_LIMIT: Int = 0x20

    fun escape(value: String): String {
        val sb = StringBuilder(value.length + ESCAPE_PADDING)
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < CONTROL_CHAR_LIMIT) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
