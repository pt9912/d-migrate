package dev.dmigrate.core.diff

/**
 * F.5 Sub-Slice A (2026-05-19): conservative heuristic for detecting
 * cross-table references inside a CHECK constraint expression.
 *
 * The first F.5 Vollscheibe slice does NOT parse the SQL semantically;
 * the heuristic is intentionally simple: any word-boundary `SELECT`
 * token (case-insensitive) inside the raw expression text marks the
 * CHECK as "potentially referencing another table" — typical patterns
 * are `EXISTS (SELECT 1 FROM other)` or `(SELECT max(x) FROM other) > 0`.
 *
 * The trade-off is conservative on purpose:
 *
 * - **False positives** are acceptable: an operator who writes a CHECK
 *   like `selection_count > 0` (column called `selection_count`) trips
 *   the heuristic and blocks. The blocker code carries a clear message
 *   so the operator can rename the column or accept the block.
 * - **False negatives** are NOT acceptable: a real cross-table
 *   sub-query MUST be caught. Even if the operator obfuscates with
 *   whitespace, the word-boundary regex catches every legal SQL
 *   keyword `SELECT`.
 *
 * SQL comments (`-- …` line comments, `/* … */` block comments) inside
 * the expression are stripped before the regex runs so a commented-out
 * `SELECT` keyword does not trigger a false positive.
 *
 * Out of scope:
 *
 * - Semantic SQL parsing (see F.5 plan §9 Out-of-scope) — that's a
 *   separate workstream.
 * - Multi-line normalisation: lives in
 *   [ConstraintDiffContract.canonicalRawSqlExpression]; the heuristic
 *   runs on the canonicalised text.
 */
internal object CrossTableCheckHeuristic {

    private val SELECT_TOKEN = Regex("\\bselect\\b", RegexOption.IGNORE_CASE)
    private val LINE_COMMENT = Regex("--[^\n]*")
    private val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

    fun hasCrossTableReference(expression: String?): Boolean {
        if (expression.isNullOrBlank()) return false
        val stripped = expression
            .replace(BLOCK_COMMENT, " ")
            .replace(LINE_COMMENT, " ")
        return SELECT_TOKEN.containsMatchIn(stripped)
    }
}
