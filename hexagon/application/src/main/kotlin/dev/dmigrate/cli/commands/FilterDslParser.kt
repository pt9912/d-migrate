package dev.dmigrate.cli.commands

/**
 * Handwritten recursive-descent parser for the d-migrate filter DSL.
 *
 * Replaces raw SQL `--filter` passthrough with a closed, safe grammar.
 * The parser produces an [FilterExpr] AST that is subsequently translated
 * into [dev.dmigrate.core.data.DataFilter.ParameterizedClause] with
 * bind parameters for all literals.
 *
 * Grammar (precedence low→high):
 * ```
 * filter_expr := or_expr
 * or_expr     := and_expr ("OR" and_expr)*
 * and_expr    := not_expr ("AND" not_expr)*
 * not_expr    := "NOT" not_expr | predicate
 * predicate   := value_expr op value_expr
 *              | value_expr "IN" "(" value_list ")"
 *              | value_expr "IS" "NULL"
 *              | value_expr "IS" "NOT" "NULL"
 *              | "(" filter_expr ")"
 * value_expr  := add_expr
 * add_expr    := mul_expr (("+" | "-") mul_expr)*
 * mul_expr    := unary_expr (("*" | "/") unary_expr)*
 * unary_expr  := "-" atom | atom
 * atom        := literal | function_call | qualified_identifier | "(" value_expr ")"
 * ```
 */
object FilterDslParser {
    private val GROUP_TERMINATORS = setOf("AND", "OR")

    fun parse(raw: String): FilterDslParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return FilterDslParseResult.Failure(
                FilterDslParseError("Filter expression must not be empty", null, 0)
            )
        }
        val tokens = try {
            FilterDslTokenizer.tokenize(trimmed)
        } catch (e: FilterDslParseException) {
            return FilterDslParseResult.Failure(e.error)
        }
        if (tokens.isEmpty()) {
            return FilterDslParseResult.Failure(
                FilterDslParseError("Filter expression must not be empty", null, 0)
            )
        }
        val state = FilterDslParserState(tokens)
        return try {
            val expr = parseOrExpr(state)
            if (!state.isAtEnd()) {
                val tok = state.peek()
                throw state.error("Unexpected token '${tok.text}'", tok)
            }
            FilterDslParseResult.Success(expr)
        } catch (e: FilterDslParseException) {
            FilterDslParseResult.Failure(e.error)
        }
    }

    // ── Recursive descent ──────────────────────────────────────

    private fun parseOrExpr(s: FilterDslParserState): FilterExpr {
        var left = parseAndExpr(s)
        while (s.matchKeyword("OR") != null) {
            val right = parseAndExpr(s)
            left = FilterExpr.Or(left, right)
        }
        return left
    }

    private fun parseAndExpr(s: FilterDslParserState): FilterExpr {
        var left = parseNotExpr(s)
        while (s.matchKeyword("AND") != null) {
            val right = parseNotExpr(s)
            left = FilterExpr.And(left, right)
        }
        return left
    }

    private fun parseNotExpr(s: FilterDslParserState): FilterExpr {
        if (s.matchKeyword("NOT") != null) {
            val inner = parseNotExpr(s)
            return FilterExpr.Not(inner)
        }
        return parsePredicate(s)
    }

    private fun parsePredicate(s: FilterDslParserState): FilterExpr {
        // Grouped filter expression: ( filter_expr )
        // Use backtracking to distinguish from (value_expr) in comparisons.
        if (!s.isAtEnd() && s.peek().type == TokenType.LPAREN) {
            val saved = s.save()
            val result = try {
                s.advance() // consume '('
                val inner = parseOrExpr(s)
                s.expect(TokenType.RPAREN, "')'")
                // Valid grouped filter if followed by AND/OR/end/closing-paren
                if (isGroupedFilterTail(s)) {
                    FilterExpr.Group(inner)
                } else {
                    null // not a filter group, backtrack
                }
            } catch (_: FilterDslParseException) {
                null
            }
            if (result != null) return result
            s.restore(saved)
        }

        val left = FilterDslValueParser.parseValueExpr(s)

        if (s.isAtEnd()) {
            throw FilterDslParseException(
                FilterDslParseError("Expected operator after expression", null,
                    if (left is ValueExpr.Identifier) left.pos else 0)
            )
        }

        val tok = s.peek()

        // IS NULL / IS NOT NULL
        if (tok.type == TokenType.KEYWORD && tok.text == "IS") {
            s.advance()
            val notTok = s.matchKeyword("NOT")
            s.expectKeyword("NULL")
            return if (notTok != null) FilterExpr.IsNotNull(left) else FilterExpr.IsNull(left)
        }

        // IN (...)
        if (tok.type == TokenType.KEYWORD && tok.text == "IN") {
            s.advance()
            s.expect(TokenType.LPAREN, "'('")
            val values = mutableListOf(FilterDslValueParser.parseValueExpr(s))
            while (s.matchType(TokenType.COMMA) != null) {
                values += FilterDslValueParser.parseValueExpr(s)
            }
            s.expect(TokenType.RPAREN, "')'")
            // Reject null in IN lists (§4.5)
            for (v in values) {
                if (v is ValueExpr.NullKeyword) {
                    throw FilterDslParseException(
                        FilterDslParseError(
                            "Cannot use 'null' inside IN (...); use IS NULL or IS NOT NULL instead",
                            "null",
                            v.pos,
                        )
                    )
                }
            }
            return FilterExpr.In(left, values)
        }

        // Comparison operators
        if (tok.type == TokenType.OP) {
            val op = s.advance()
            val right = FilterDslValueParser.parseValueExpr(s)
            checkNullLiteral(right, op)
            return FilterExpr.Comparison(left, op.text, right)
        }

        throw s.error("Expected operator, 'IS', or 'IN' but found '${tok.text}'", tok)
    }

    private fun isGroupedFilterTail(state: FilterDslParserState): Boolean {
        if (state.isAtEnd()) return true
        val token = state.peek()
        if (token.type == TokenType.RPAREN) return true
        return token.type == TokenType.KEYWORD && token.text in GROUP_TERMINATORS
    }

    private fun checkNullLiteral(expr: ValueExpr, opToken: Token) {
        if (expr is ValueExpr.NullKeyword) {
            throw FilterDslParseException(
                FilterDslParseError(
                    "Cannot use 'null' as a value with '${opToken.text}'; use IS NULL or IS NOT NULL instead",
                    "null",
                    expr.pos,
                )
            )
        }
    }
}

// ── AST types ──────────────────────────────────────────────────

sealed class FilterExpr {
    data class Comparison(val left: ValueExpr, val op: String, val right: ValueExpr) : FilterExpr()
    data class In(val left: ValueExpr, val values: List<ValueExpr>) : FilterExpr()
    data class IsNull(val expr: ValueExpr) : FilterExpr()
    data class IsNotNull(val expr: ValueExpr) : FilterExpr()
    data class And(val left: FilterExpr, val right: FilterExpr) : FilterExpr()
    data class Or(val left: FilterExpr, val right: FilterExpr) : FilterExpr()
    data class Not(val inner: FilterExpr) : FilterExpr()
    data class Group(val inner: FilterExpr) : FilterExpr()
}

sealed class ValueExpr {
    abstract val pos: Int

    data class IntLiteral(val value: Long, override val pos: Int = 0) : ValueExpr()
    data class DecLiteral(val value: java.math.BigDecimal, override val pos: Int = 0) : ValueExpr()
    data class StrLiteral(val value: String, override val pos: Int = 0) : ValueExpr()
    data class BoolLiteral(val value: Boolean, override val pos: Int = 0) : ValueExpr()
    data class NullKeyword(override val pos: Int = 0) : ValueExpr()
    data class Identifier(val name: String, override val pos: Int = 0) : ValueExpr()
    data class FunctionCall(val name: String, val args: List<ValueExpr>, override val pos: Int = 0) : ValueExpr()
    data class Arithmetic(val left: ValueExpr, val op: String, val right: ValueExpr, override val pos: Int = 0) : ValueExpr()
    data class UnaryMinus(val inner: ValueExpr, override val pos: Int = 0) : ValueExpr()
    data class GroupedValue(val inner: ValueExpr, override val pos: Int = 0) : ValueExpr()
}

// ── Parse result / error ───────────────────────────────────────

sealed class FilterDslParseResult {
    data class Success(val expr: FilterExpr) : FilterDslParseResult()
    data class Failure(val error: FilterDslParseError) : FilterDslParseResult()
}

data class FilterDslParseError(
    val message: String,
    val token: String?,
    val index: Int?,
)

internal class FilterDslParseException(val error: FilterDslParseError) : RuntimeException(error.message)

// ── Public parse + exception API ───────────────────────────────

/** Thrown when a `--filter` value cannot be parsed as the 0.9.3 DSL. */
class FilterParseException(val parseError: FilterDslParseError) :
    RuntimeException(parseError.message)

/**
 * Parses a raw `--filter` CLI string into a [ParsedFilter].
 * Returns `null` if [rawFilter] is null (flag not provided).
 *
 * @throws FilterParseException if [rawFilter] is blank or not DSL-conformant
 */
fun parseFilter(rawFilter: String?): ParsedFilter? {
    if (rawFilter == null) return null
    if (rawFilter.isBlank()) {
        throw FilterParseException(
            FilterDslParseError("Filter expression must not be empty or whitespace-only", null, 0)
        )
    }
    val trimmed = rawFilter.trim()
    return when (val result = FilterDslParser.parse(trimmed)) {
        is FilterDslParseResult.Success -> ParsedFilter(
            expr = result.expr,
            canonical = FilterDslTranslator.canonicalize(result.expr),
        )
        is FilterDslParseResult.Failure -> throw FilterParseException(result.error)
    }
}
