package dev.dmigrate.cli.commands

import dev.dmigrate.core.data.DataFilter
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers

/**
 * Translates a parsed [FilterExpr] AST into:
 * 1. A canonical fingerprint string (for resume/checkpoint stability)
 * 2. A [DataFilter.ParameterizedClause] with `?` placeholders and bind params
 */
object FilterDslTranslator {

    /**
     * Produces a canonical string form for fingerprinting.
     *
     * Rules:
     * - Keywords and function names are uppercased
     * - Identifiers are ASCII-lowercased (segment-wise)
     * - Redundant whitespace removed, single space separators
     * - Bool literals: `true` / `false`
     * - Integer/decimal literals in canonical form
     * - Parentheses preserved as-is (no elision)
     */
    fun canonicalize(expr: FilterExpr): String = buildString { appendFilter(expr) }

    /**
     * Translates a [FilterExpr] AST into a [DataFilter.ParameterizedClause].
     * All literals become `?` bind parameters; identifiers are dialect-quoted.
     */
    fun toParameterizedClause(expr: FilterExpr, dialect: DatabaseDialect): DataFilter.ParameterizedClause {
        val params = mutableListOf<Any?>()
        val sql = buildString { emitFilterSql(expr, dialect, params) }
        return DataFilter.ParameterizedClause(sql, params)
    }

    // ── Canonical fingerprint ──────────────────────────────────

    private fun StringBuilder.appendFilter(expr: FilterExpr) {
        when (expr) {
            is FilterExpr.Comparison -> {
                appendValue(expr.left)
                append(" ${expr.op} ")
                appendValue(expr.right)
            }
            is FilterExpr.In -> {
                appendValue(expr.left)
                append(" IN (")
                expr.values.forEachIndexed { i, v ->
                    if (i > 0) append(", ")
                    appendValue(v)
                }
                append(")")
            }
            is FilterExpr.IsNull -> {
                appendValue(expr.expr)
                append(" IS NULL")
            }
            is FilterExpr.IsNotNull -> {
                appendValue(expr.expr)
                append(" IS NOT NULL")
            }
            is FilterExpr.And -> {
                appendFilter(expr.left)
                append(" AND ")
                appendFilter(expr.right)
            }
            is FilterExpr.Or -> {
                appendFilter(expr.left)
                append(" OR ")
                appendFilter(expr.right)
            }
            is FilterExpr.Not -> {
                append("NOT ")
                appendFilter(expr.inner)
            }
            is FilterExpr.Group -> {
                append("(")
                appendFilter(expr.inner)
                append(")")
            }
        }
    }

    private fun StringBuilder.appendValue(expr: ValueExpr) {
        when (expr) {
            is ValueExpr.IntLiteral -> append(expr.value)
            is ValueExpr.DecLiteral -> append(expr.value.toPlainString())
            is ValueExpr.StrLiteral -> {
                append("'")
                append(expr.value.replace("'", "''"))
                append("'")
            }
            is ValueExpr.BoolLiteral -> append(if (expr.value) "true" else "false")
            is ValueExpr.NullKeyword -> append("NULL")
            is ValueExpr.Identifier -> append(canonicalizeIdentifier(expr.name))
            is ValueExpr.FunctionCall -> {
                append(expr.name) // already uppercased by parser
                append("(")
                expr.args.forEachIndexed { i, a ->
                    if (i > 0) append(", ")
                    appendValue(a)
                }
                append(")")
            }
            is ValueExpr.Arithmetic -> {
                appendValue(expr.left)
                append(" ${expr.op} ")
                appendValue(expr.right)
            }
            is ValueExpr.UnaryMinus -> {
                append("-")
                appendValue(expr.inner)
            }
            is ValueExpr.GroupedValue -> {
                append("(")
                appendValue(expr.inner)
                append(")")
            }
        }
    }

    private fun canonicalizeIdentifier(name: String): String =
        name.split('.').joinToString(".") { it.lowercase(java.util.Locale.ROOT) }

    // ── SQL emission ───────────────────────────────────────────

    private fun StringBuilder.emitFilterSql(
        expr: FilterExpr,
        dialect: DatabaseDialect,
        params: MutableList<Any?>,
    ) {
        when (expr) {
            is FilterExpr.Comparison -> {
                emitValueSql(expr.left, dialect, params)
                append(" ${expr.op} ")
                emitValueSql(expr.right, dialect, params)
            }
            is FilterExpr.In -> {
                emitValueSql(expr.left, dialect, params)
                append(" IN (")
                expr.values.forEachIndexed { i, v ->
                    if (i > 0) append(", ")
                    emitValueSql(v, dialect, params)
                }
                append(")")
            }
            is FilterExpr.IsNull -> {
                emitValueSql(expr.expr, dialect, params)
                append(" IS NULL")
            }
            is FilterExpr.IsNotNull -> {
                emitValueSql(expr.expr, dialect, params)
                append(" IS NOT NULL")
            }
            is FilterExpr.And -> {
                emitFilterSql(expr.left, dialect, params)
                append(" AND ")
                emitFilterSql(expr.right, dialect, params)
            }
            is FilterExpr.Or -> {
                append("(")
                emitFilterSql(expr.left, dialect, params)
                append(" OR ")
                emitFilterSql(expr.right, dialect, params)
                append(")")
            }
            is FilterExpr.Not -> {
                append("NOT ")
                emitFilterSql(expr.inner, dialect, params)
            }
            is FilterExpr.Group -> {
                append("(")
                emitFilterSql(expr.inner, dialect, params)
                append(")")
            }
        }
    }

    private fun StringBuilder.emitValueSql(
        expr: ValueExpr,
        dialect: DatabaseDialect,
        params: MutableList<Any?>,
    ) {
        when (expr) {
            is ValueExpr.IntLiteral -> {
                append("?")
                params += expr.value
            }
            is ValueExpr.DecLiteral -> {
                append("?")
                params += expr.value
            }
            is ValueExpr.StrLiteral -> {
                append("?")
                params += expr.value
            }
            is ValueExpr.BoolLiteral -> {
                append("?")
                params += expr.value
            }
            is ValueExpr.NullKeyword -> append("NULL")
            is ValueExpr.Identifier -> {
                append(SqlIdentifiers.quoteQualifiedIdentifier(expr.name, dialect))
            }
            is ValueExpr.FunctionCall -> {
                append(expr.name)
                append("(")
                expr.args.forEachIndexed { i, a ->
                    if (i > 0) append(", ")
                    emitValueSql(a, dialect, params)
                }
                append(")")
            }
            is ValueExpr.Arithmetic -> {
                emitValueSql(expr.left, dialect, params)
                append(" ${expr.op} ")
                emitValueSql(expr.right, dialect, params)
            }
            is ValueExpr.UnaryMinus -> {
                append("-")
                emitValueSql(expr.inner, dialect, params)
            }
            is ValueExpr.GroupedValue -> {
                append("(")
                emitValueSql(expr.inner, dialect, params)
                append(")")
            }
        }
    }
}
