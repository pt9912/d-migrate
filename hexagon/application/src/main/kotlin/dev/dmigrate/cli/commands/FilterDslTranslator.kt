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

    fun canonicalize(expr: FilterExpr): String = CanonicalRenderer().render(expr)

    fun toParameterizedClause(expr: FilterExpr, dialect: DatabaseDialect): DataFilter.ParameterizedClause {
        val renderer = SqlRenderer(dialect)
        return DataFilter.ParameterizedClause(renderer.render(expr), renderer.params)
    }

    private interface FilterAstVisitor {
        fun visit(expr: FilterExpr.Comparison)
        fun visit(expr: FilterExpr.In)
        fun visit(expr: FilterExpr.IsNull)
        fun visit(expr: FilterExpr.IsNotNull)
        fun visit(expr: FilterExpr.And)
        fun visit(expr: FilterExpr.Or)
        fun visit(expr: FilterExpr.Not)
        fun visit(expr: FilterExpr.Group)
        fun visit(expr: ValueExpr.IntLiteral)
        fun visit(expr: ValueExpr.DecLiteral)
        fun visit(expr: ValueExpr.StrLiteral)
        fun visit(expr: ValueExpr.BoolLiteral)
        fun visit(expr: ValueExpr.NullKeyword)
        fun visit(expr: ValueExpr.Identifier)
        fun visit(expr: ValueExpr.FunctionCall)
        fun visit(expr: ValueExpr.Arithmetic)
        fun visit(expr: ValueExpr.UnaryMinus)
        fun visit(expr: ValueExpr.GroupedValue)
    }

    private abstract class RenderingVisitor : FilterAstVisitor {
        protected val out = StringBuilder()

        fun render(expr: FilterExpr): String {
            expr.accept(this)
            return out.toString()
        }

        override fun visit(expr: FilterExpr.Comparison) {
            expr.left.accept(this)
            out.append(" ${expr.op} ")
            expr.right.accept(this)
        }

        override fun visit(expr: FilterExpr.In) {
            expr.left.accept(this)
            out.append(" IN (")
            expr.values.forEachIndexed { index, value ->
                if (index > 0) out.append(", ")
                value.accept(this)
            }
            out.append(")")
        }

        override fun visit(expr: FilterExpr.IsNull) {
            expr.expr.accept(this)
            out.append(" IS NULL")
        }

        override fun visit(expr: FilterExpr.IsNotNull) {
            expr.expr.accept(this)
            out.append(" IS NOT NULL")
        }

        override fun visit(expr: FilterExpr.And) {
            expr.left.accept(this)
            out.append(" AND ")
            expr.right.accept(this)
        }

        override fun visit(expr: FilterExpr.Or) {
            appendOrStart()
            expr.left.accept(this)
            out.append(" OR ")
            expr.right.accept(this)
            appendOrEnd()
        }

        override fun visit(expr: FilterExpr.Not) {
            out.append("NOT ")
            expr.inner.accept(this)
        }

        override fun visit(expr: FilterExpr.Group) {
            out.append("(")
            expr.inner.accept(this)
            out.append(")")
        }

        override fun visit(expr: ValueExpr.FunctionCall) {
            out.append(expr.name)
            out.append("(")
            expr.args.forEachIndexed { index, arg ->
                if (index > 0) out.append(", ")
                arg.accept(this)
            }
            out.append(")")
        }

        override fun visit(expr: ValueExpr.Arithmetic) {
            expr.left.accept(this)
            out.append(" ${expr.op} ")
            expr.right.accept(this)
        }

        override fun visit(expr: ValueExpr.UnaryMinus) {
            out.append("-")
            expr.inner.accept(this)
        }

        override fun visit(expr: ValueExpr.GroupedValue) {
            out.append("(")
            expr.inner.accept(this)
            out.append(")")
        }

        protected open fun appendOrStart() {}
        protected open fun appendOrEnd() {}
    }

    private class CanonicalRenderer : RenderingVisitor() {
        override fun visit(expr: ValueExpr.IntLiteral) {
            out.append(expr.value)
        }

        override fun visit(expr: ValueExpr.DecLiteral) {
            out.append(expr.value.toPlainString())
        }

        override fun visit(expr: ValueExpr.StrLiteral) {
            out.append("'")
            out.append(expr.value.replace("'", "''"))
            out.append("'")
        }

        override fun visit(expr: ValueExpr.BoolLiteral) {
            out.append(if (expr.value) "true" else "false")
        }

        override fun visit(expr: ValueExpr.NullKeyword) {
            out.append("NULL")
        }

        override fun visit(expr: ValueExpr.Identifier) {
            out.append(expr.name.split('.').joinToString(".") { it.lowercase(java.util.Locale.ROOT) })
        }
    }

    private class SqlRenderer(
        private val dialect: DatabaseDialect,
    ) : RenderingVisitor() {
        val params = mutableListOf<Any?>()

        override fun visit(expr: ValueExpr.IntLiteral) {
            out.append("?")
            params += expr.value
        }

        override fun visit(expr: ValueExpr.DecLiteral) {
            out.append("?")
            params += expr.value
        }

        override fun visit(expr: ValueExpr.StrLiteral) {
            out.append("?")
            params += expr.value
        }

        override fun visit(expr: ValueExpr.BoolLiteral) {
            out.append("?")
            params += expr.value
        }

        override fun visit(expr: ValueExpr.NullKeyword) {
            out.append("NULL")
        }

        override fun visit(expr: ValueExpr.Identifier) {
            out.append(SqlIdentifiers.quoteQualifiedIdentifier(expr.name, dialect))
        }

        override fun appendOrStart() {
            out.append("(")
        }

        override fun appendOrEnd() {
            out.append(")")
        }
    }

    private fun FilterExpr.accept(visitor: FilterAstVisitor) {
        when (this) {
            is FilterExpr.Comparison -> visitor.visit(this)
            is FilterExpr.In -> visitor.visit(this)
            is FilterExpr.IsNull -> visitor.visit(this)
            is FilterExpr.IsNotNull -> visitor.visit(this)
            is FilterExpr.And -> visitor.visit(this)
            is FilterExpr.Or -> visitor.visit(this)
            is FilterExpr.Not -> visitor.visit(this)
            is FilterExpr.Group -> visitor.visit(this)
        }
    }

    private fun ValueExpr.accept(visitor: FilterAstVisitor) {
        when (this) {
            is ValueExpr.IntLiteral -> visitor.visit(this)
            is ValueExpr.DecLiteral -> visitor.visit(this)
            is ValueExpr.StrLiteral -> visitor.visit(this)
            is ValueExpr.BoolLiteral -> visitor.visit(this)
            is ValueExpr.NullKeyword -> visitor.visit(this)
            is ValueExpr.Identifier -> visitor.visit(this)
            is ValueExpr.FunctionCall -> visitor.visit(this)
            is ValueExpr.Arithmetic -> visitor.visit(this)
            is ValueExpr.UnaryMinus -> visitor.visit(this)
            is ValueExpr.GroupedValue -> visitor.visit(this)
        }
    }
}
