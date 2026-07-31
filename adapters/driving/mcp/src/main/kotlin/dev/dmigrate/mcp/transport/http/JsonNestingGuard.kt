package dev.dmigrate.mcp.transport.http

/**
 * Transport-boundary guard against pathologically deep JSON nesting — explicit
 * defense-in-depth for the recursive-descent parse in
 * [org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler].
 *
 * Verified behaviour (Security-Audit follow-up): with the currently resolved
 * Gson — which enforces a structural nesting limit — deep input raises a
 * `MalformedJsonException` that lsp4j wraps into a `MessageIssueException`. That
 * is an [Exception], so `parseBody`'s `catch (e: Exception)` already turns it
 * into a clean `400`; the originally-feared uncaught [StackOverflowError] does
 * NOT occur with this dependency set.
 *
 * This guard exists so that property does not silently depend on a transitive
 * default: lsp4j wraps only `JsonParseException`, not [Error], so a Gson
 * downgrade below its nesting limit would let a real `StackOverflowError` escape
 * the `catch (e: Exception)`. Scanning the raw body BEFORE the parser and
 * rejecting nesting beyond [MAX_DEPTH] makes the bound owned at the transport
 * boundary — the same boundary as the inbound byte cap (`checkBodySize`), which
 * does not close this on its own (a length-bounded body can still nest hundreds
 * of thousands of levels deep).
 *
 * The scan is string- and escape-aware, so a `"[[[["` string literal never
 * counts as structural nesting. It returns as soon as the limit is exceeded, so
 * an over-deep body is rejected in O([MAX_DEPTH]) work, not O(body length).
 */
internal object JsonNestingGuard {

    /**
     * Maximum structural nesting depth of `{` / `[`. Legitimate JSON-RPC / MCP
     * payloads nest only a handful of levels; 200 leaves a wide margin above
     * real use while staying far below the thousands of frames a Gson recursive
     * descent needs to overflow a default JVM stack (and below Gson 2.11's own
     * 255 default, so this guard is the deterministic gate regardless of the
     * resolved Gson version).
     */
    const val MAX_DEPTH = 200

    /**
     * Returns `true` when the structural nesting depth of [json] exceeds
     * [maxDepth] at any point. Brackets inside string literals are ignored.
     */
    fun exceedsMaxDepth(json: String, maxDepth: Int = MAX_DEPTH): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in json) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> {
                    depth++
                    if (depth > maxDepth) return true
                }
                '}', ']' -> if (depth > 0) depth--
            }
        }
        return false
    }
}
