package dev.dmigrate.driver.postgresql

/**
 * F.5 Sub-Slice F: PostgreSQL EXCLUDE constraints support a rich
 * exclude-element grammar:
 *
 * ```
 * EXCLUDE [USING method] (exclude_element WITH operator [, …])
 * exclude_element := { column_name | (expression) }
 *                    [COLLATE collation] [opclass]
 *                    [ASC | DESC] [NULLS { FIRST | LAST }]
 * ```
 *
 * The first F.5 tranche round-trips three minimal element-head
 * shapes:
 *
 * 1. a single bare column name (`room`, `room_id`);
 * 2. a single double-quoted identifier (`"My Column"`);
 * 3. a balanced-parenthesis functional expression
 *    (`(room + 1)`, `(lower(name))`).
 *
 * Each must be immediately followed by `WITH operator`. Anything
 * else (custom operator class, `COLLATE`, `ASC`/`DESC`, `NULLS …`,
 * mixing two identifiers without parens) is rejected with
 * [PlannerBlockerClassifier.EXCLUDE_OPERATOR_CLASS_NOT_SUPPORTED_CODE]
 * so the migration does not silently emit DDL whose inverse the
 * renderer cannot reconstruct deterministically.
 *
 * The gate is intentionally conservative: false positives (operator
 * uses an exotic-but-valid shape we reject) are acceptable and surface
 * as a manual-action blocker the operator can override; false
 * negatives (we emit DDL with a custom opclass that later fails
 * round-trip) are not.
 *
 * The gate inspects [verdict] *before* invoking
 * [PostgresDiffSqlBuilders.constraintLine] — the SQL builder still
 * interpolates the expression as-is once the verdict allows it.
 */
internal object ExcludeOperatorClassGate {

    /**
     * Convenience wrapper around [verdict] for the renderer hot paths
     * that only need the boolean "is this safe to emit?" answer.
     */
    fun isAllowed(expression: String?): Boolean = verdict(expression) is Verdict.Allowed

    /**
     * Classify an EXCLUDE expression. An empty / blank expression is
     * NOT this gate's concern — the upstream `constraintLine` already
     * returns `null` for that case and the standard
     * `DIALECT_UNSUPPORTED_OPERATION` block kicks in. Passing a blank
     * string returns [Verdict.Allowed] so the caller's blank-handling
     * branch stays the single source of truth.
     */
    fun verdict(expression: String?): Verdict {
        if (expression.isNullOrBlank()) return Verdict.Allowed
        val elements = splitTopLevel(expression)
        for (element in elements) {
            val withSplit = splitOnWith(element)
                ?: return Verdict.Blocked(
                    reason = "missing `WITH operator` clause",
                    offendingElement = element.trim(),
                )
            val left = withSplit.first.trim()
            val right = withSplit.second.trim()
            if (right.isEmpty()) {
                return Verdict.Blocked(
                    reason = "missing operator after `WITH`",
                    offendingElement = element.trim(),
                )
            }
            if (!isAllowedElementHead(left)) {
                return Verdict.Blocked(
                    reason = "exclude element head `$left` carries an operator class, " +
                        "collation or ordering token — only bare column names or " +
                        "single parenthesised expressions are supported",
                    offendingElement = element.trim(),
                )
            }
        }
        return Verdict.Allowed
    }

    /**
     * Split a comma-separated EXCLUDE element list while respecting
     * balanced parentheses, so `(a, b) WITH =` stays one element.
     * String literals are not handled — operators do not generally
     * appear inside literals in EXCLUDE expressions, and the trade-off
     * keeps this parser tiny.
     */
    private fun splitTopLevel(expression: String): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var depth = 0
        for (ch in expression) {
            when (ch) {
                '(' -> { depth++; buf.append(ch) }
                ')' -> { depth = maxOf(0, depth - 1); buf.append(ch) }
                ',' -> if (depth == 0) {
                    out += buf.toString()
                    buf.clear()
                } else {
                    buf.append(ch)
                }
                else -> buf.append(ch)
            }
        }
        if (buf.isNotEmpty()) out += buf.toString()
        return out
    }

    /**
     * Split an element on the LAST top-level ` WITH ` token
     * (case-insensitive). Returning `null` means the element does not
     * contain a recognisable `WITH operator` clause.
     */
    private fun splitOnWith(element: String): Pair<String, String>? {
        val upper = element.uppercase()
        var depth = 0
        var lastIdx = -1
        var i = 0
        while (i <= upper.length - WITH_TOKEN.length) {
            val ch = upper[i]
            when (ch) {
                '(' -> depth++
                ')' -> depth = maxOf(0, depth - 1)
                else -> if (depth == 0 && upper.regionMatches(i, WITH_TOKEN, 0, WITH_TOKEN.length)) {
                    lastIdx = i
                }
            }
            i++
        }
        if (lastIdx < 0) return null
        val left = element.substring(0, lastIdx)
        val right = element.substring(lastIdx + WITH_TOKEN.length)
        return left to right
    }

    /**
     * The element head (before `WITH`) is allowed when it is one of:
     * - a single bare identifier (`name`, `room_id`);
     * - a single double-quoted identifier (`"My Column"`);
     * - a balanced-parenthesis expression (`(col)`, `(col + 1)`,
     *   `(lower(name))`) and nothing else.
     *
     * Anything that adds a second top-level token (`col opclass`,
     * `col DESC`, `col COLLATE "C"`) flips the verdict.
     */
    private fun isAllowedElementHead(left: String): Boolean {
        if (left.isEmpty()) return false
        if (left.matches(BARE_IDENTIFIER)) return true
        if (left.matches(QUOTED_IDENTIFIER)) return true
        if (!left.startsWith('(') || !left.endsWith(')')) return false
        var depth = 0
        for ((idx, ch) in left.withIndex()) {
            when (ch) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && idx != left.lastIndex) return false
                }
            }
        }
        return depth == 0
    }

    private val BARE_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")
    private val QUOTED_IDENTIFIER = Regex("^\"[^\"]*\"$")
    private const val WITH_TOKEN = " WITH "

    sealed interface Verdict {
        object Allowed : Verdict
        data class Blocked(val reason: String, val offendingElement: String) : Verdict
    }
}
