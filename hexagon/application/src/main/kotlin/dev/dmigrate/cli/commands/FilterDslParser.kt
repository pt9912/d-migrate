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

    fun parse(raw: String): FilterDslParseResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return FilterDslParseResult.Failure(
                FilterDslParseError("Filter expression must not be empty", null, 0)
            )
        }
        val tokens = try {
            tokenize(trimmed)
        } catch (e: FilterDslParseException) {
            return FilterDslParseResult.Failure(e.error)
        }
        if (tokens.isEmpty()) {
            return FilterDslParseResult.Failure(
                FilterDslParseError("Filter expression must not be empty", null, 0)
            )
        }
        val state = ParserState(tokens)
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

    // ── Tokenizer ──────────────────────────────────────────────

    private enum class TokenType {
        IDENTIFIER, INTEGER, DECIMAL, STRING, BOOL,
        KEYWORD, // AND, OR, NOT, IN, IS, NULL
        OP,      // = != > >= < <=
        ARITH,   // + - * /
        LPAREN, RPAREN, COMMA, DOT,
    }

    private data class Token(
        val type: TokenType,
        val text: String,
        val pos: Int,
    )

    private val KEYWORDS = setOf("AND", "OR", "NOT", "IN", "IS", "NULL")

    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { tokens += Token(TokenType.LPAREN, "(", i); i++ }
                c == ')' -> { tokens += Token(TokenType.RPAREN, ")", i); i++ }
                c == ',' -> { tokens += Token(TokenType.COMMA, ",", i); i++ }
                c == '.' -> { tokens += Token(TokenType.DOT, ".", i); i++ }
                c == '=' -> { tokens += Token(TokenType.OP, "=", i); i++ }
                c == '!' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens += Token(TokenType.OP, "!=", i); i += 2
                }
                c == '>' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens += Token(TokenType.OP, ">=", i); i += 2
                }
                c == '<' && i + 1 < input.length && input[i + 1] == '=' -> {
                    tokens += Token(TokenType.OP, "<=", i); i += 2
                }
                c == '>' -> { tokens += Token(TokenType.OP, ">", i); i++ }
                c == '<' -> { tokens += Token(TokenType.OP, "<", i); i++ }
                c == '+' -> { tokens += Token(TokenType.ARITH, "+", i); i++ }
                c == '*' -> { tokens += Token(TokenType.ARITH, "*", i); i++ }
                c == '/' -> { tokens += Token(TokenType.ARITH, "/", i); i++ }
                c == '-' -> { tokens += Token(TokenType.ARITH, "-", i); i++ }
                c == '\'' -> {
                    val start = i
                    i++ // skip opening quote
                    val sb = StringBuilder()
                    while (i < input.length) {
                        if (input[i] == '\'') {
                            if (i + 1 < input.length && input[i + 1] == '\'') {
                                sb.append('\'')
                                i += 2
                            } else {
                                break
                            }
                        } else {
                            sb.append(input[i])
                            i++
                        }
                    }
                    if (i >= input.length) {
                        throw FilterDslParseException(
                            FilterDslParseError("Unterminated string literal", null, start)
                        )
                    }
                    i++ // skip closing quote
                    tokens += Token(TokenType.STRING, sb.toString(), start)
                }
                c.isDigit() -> {
                    val start = i
                    while (i < input.length && input[i].isDigit()) i++
                    if (i < input.length && input[i] == '.' && i + 1 < input.length && input[i + 1].isDigit()) {
                        i++ // skip dot
                        while (i < input.length && input[i].isDigit()) i++
                        val text = input.substring(start, i)
                        val intPart = text.substringBefore('.')
                        if (intPart.length > 1 && intPart.startsWith('0')) {
                            throw FilterDslParseException(
                                FilterDslParseError("Leading zeros not allowed in numeric literal '$text'", text, start)
                            )
                        }
                        tokens += Token(TokenType.DECIMAL, text, start)
                    } else {
                        val text = input.substring(start, i)
                        if (text.length > 1 && text.startsWith('0')) {
                            throw FilterDslParseException(
                                FilterDslParseError("Leading zeros not allowed in numeric literal '$text'", text, start)
                            )
                        }
                        tokens += Token(TokenType.INTEGER, text, start)
                    }
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
                    val text = input.substring(start, i)
                    val upper = text.uppercase()
                    when {
                        upper in KEYWORDS -> tokens += Token(TokenType.KEYWORD, upper, start)
                        upper == "TRUE" || upper == "FALSE" -> tokens += Token(TokenType.BOOL, upper, start)
                        else -> tokens += Token(TokenType.IDENTIFIER, text, start)
                    }
                }
                else -> throw FilterDslParseException(
                    FilterDslParseError("Unexpected character '${c}'", c.toString(), i)
                )
            }
        }
        return tokens
    }

    // ── Parser state ───────────────────────────────────────────

    private class ParserState(private val tokens: List<Token>) {
        private var pos = 0

        fun isAtEnd(): Boolean = pos >= tokens.size
        fun peek(): Token = tokens[pos]
        fun advance(): Token = tokens[pos++]
        fun save(): Int = pos
        fun restore(saved: Int) { pos = saved }

        fun matchKeyword(vararg keywords: String): Token? {
            if (isAtEnd()) return null
            val tok = peek()
            if (tok.type == TokenType.KEYWORD && tok.text in keywords) {
                return advance()
            }
            return null
        }

        fun matchType(type: TokenType): Token? {
            if (isAtEnd()) return null
            if (peek().type == type) return advance()
            return null
        }

        fun expect(type: TokenType, description: String): Token {
            if (isAtEnd()) {
                val lastPos = if (tokens.isNotEmpty()) tokens.last().pos + tokens.last().text.length else 0
                throw FilterDslParseException(
                    FilterDslParseError("Expected $description but reached end of input", null, lastPos)
                )
            }
            val tok = peek()
            if (tok.type != type) {
                throw error("Expected $description but found '${tok.text}'", tok)
            }
            return advance()
        }

        fun expectKeyword(keyword: String): Token {
            if (isAtEnd()) {
                val lastPos = if (tokens.isNotEmpty()) tokens.last().pos + tokens.last().text.length else 0
                throw FilterDslParseException(
                    FilterDslParseError("Expected '$keyword' but reached end of input", null, lastPos)
                )
            }
            val tok = peek()
            if (tok.type != TokenType.KEYWORD || tok.text != keyword) {
                throw error("Expected '$keyword' but found '${tok.text}'", tok)
            }
            return advance()
        }

        fun error(message: String, tok: Token): FilterDslParseException =
            FilterDslParseException(FilterDslParseError(message, tok.text, tok.pos))
    }

    // ── Recursive descent ──────────────────────────────────────

    private fun parseOrExpr(s: ParserState): FilterExpr {
        var left = parseAndExpr(s)
        while (s.matchKeyword("OR") != null) {
            val right = parseAndExpr(s)
            left = FilterExpr.Or(left, right)
        }
        return left
    }

    private fun parseAndExpr(s: ParserState): FilterExpr {
        var left = parseNotExpr(s)
        while (s.matchKeyword("AND") != null) {
            val right = parseNotExpr(s)
            left = FilterExpr.And(left, right)
        }
        return left
    }

    private fun parseNotExpr(s: ParserState): FilterExpr {
        if (s.matchKeyword("NOT") != null) {
            val inner = parseNotExpr(s)
            return FilterExpr.Not(inner)
        }
        return parsePredicate(s)
    }

    private fun parsePredicate(s: ParserState): FilterExpr {
        // Grouped filter expression: ( filter_expr )
        // Use backtracking to distinguish from (value_expr) in comparisons.
        if (!s.isAtEnd() && s.peek().type == TokenType.LPAREN) {
            val saved = s.save()
            val result = try {
                s.advance() // consume '('
                val inner = parseOrExpr(s)
                s.expect(TokenType.RPAREN, "')'")
                // Valid grouped filter if followed by AND/OR/end/closing-paren
                if (s.isAtEnd() ||
                    (s.peek().type == TokenType.KEYWORD && s.peek().text in setOf("AND", "OR")) ||
                    s.peek().type == TokenType.RPAREN
                ) {
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

        val left = parseValueExpr(s)

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
            val values = mutableListOf(parseValueExpr(s))
            while (s.matchType(TokenType.COMMA) != null) {
                values += parseValueExpr(s)
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
            val right = parseValueExpr(s)
            checkNullLiteral(right, op)
            return FilterExpr.Comparison(left, op.text, right)
        }

        throw s.error("Expected operator, 'IS', or 'IN' but found '${tok.text}'", tok)
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

    // ── Value expressions (arithmetic) ─────────────────────────

    private fun parseValueExpr(s: ParserState): ValueExpr = parseAddExpr(s)

    private fun parseAddExpr(s: ParserState): ValueExpr {
        var left = parseMulExpr(s)
        while (!s.isAtEnd() && s.peek().type == TokenType.ARITH && s.peek().text in setOf("+", "-")) {
            val op = s.advance()
            val right = parseMulExpr(s)
            left = ValueExpr.Arithmetic(left, op.text, right)
        }
        return left
    }

    private fun parseMulExpr(s: ParserState): ValueExpr {
        var left = parseUnaryExpr(s)
        while (!s.isAtEnd() && s.peek().type == TokenType.ARITH && s.peek().text in setOf("*", "/")) {
            val op = s.advance()
            val right = parseUnaryExpr(s)
            left = ValueExpr.Arithmetic(left, op.text, right)
        }
        return left
    }

    private fun parseUnaryExpr(s: ParserState): ValueExpr {
        if (!s.isAtEnd() && s.peek().type == TokenType.ARITH && s.peek().text == "-") {
            val op = s.advance()
            val inner = parseAtom(s)
            return ValueExpr.UnaryMinus(inner)
        }
        return parseAtom(s)
    }

    private fun parseAtom(s: ParserState): ValueExpr {
        if (s.isAtEnd()) {
            throw FilterDslParseException(
                FilterDslParseError("Expected value but reached end of input", null, 0)
            )
        }
        val tok = s.peek()
        return when (tok.type) {
            TokenType.INTEGER -> {
                s.advance()
                ValueExpr.IntLiteral(tok.text.toLong(), tok.pos)
            }
            TokenType.DECIMAL -> {
                s.advance()
                ValueExpr.DecLiteral(tok.text.toBigDecimal(), tok.pos)
            }
            TokenType.STRING -> {
                s.advance()
                ValueExpr.StrLiteral(tok.text, tok.pos)
            }
            TokenType.BOOL -> {
                s.advance()
                ValueExpr.BoolLiteral(tok.text.uppercase() == "TRUE", tok.pos)
            }
            TokenType.KEYWORD -> {
                if (tok.text == "NULL") {
                    s.advance()
                    ValueExpr.NullKeyword(tok.pos)
                } else {
                    throw s.error("Unexpected keyword '${tok.text}' in value position", tok)
                }
            }
            TokenType.IDENTIFIER -> {
                s.advance()
                // Check for function call: identifier followed by '('
                if (!s.isAtEnd() && s.peek().type == TokenType.LPAREN) {
                    val fnName = tok.text
                    return parseFunctionCall(s, fnName, tok.pos)
                }
                // Check for qualified identifier: identifier.identifier
                if (!s.isAtEnd() && s.peek().type == TokenType.DOT) {
                    s.advance() // consume dot
                    val col = s.expect(TokenType.IDENTIFIER, "column name after '.'")
                    // Reject further dots
                    if (!s.isAtEnd() && s.peek().type == TokenType.DOT) {
                        throw s.error(
                            "Multi-level qualified identifiers (schema.table.column) are not supported; " +
                                "use table.column",
                            s.peek(),
                        )
                    }
                    return ValueExpr.Identifier("${tok.text}.${col.text}", tok.pos)
                }
                ValueExpr.Identifier(tok.text, tok.pos)
            }
            TokenType.LPAREN -> {
                s.advance()
                val inner = parseValueExpr(s)
                s.expect(TokenType.RPAREN, "')'")
                ValueExpr.GroupedValue(inner)
            }
            else -> throw s.error("Expected value but found '${tok.text}'", tok)
        }
    }

    // ── Function calls ─────────────────────────────────────────

    private val ALLOWED_FUNCTIONS: Map<String, IntRange> = mapOf(
        "LOWER" to 1..1,
        "UPPER" to 1..1,
        "TRIM" to 1..1,
        "LENGTH" to 1..1,
        "ABS" to 1..1,
        "ROUND" to 1..2,
        "COALESCE" to 2..Int.MAX_VALUE,
    )

    private fun parseFunctionCall(s: ParserState, name: String, pos: Int): ValueExpr {
        val upperName = name.uppercase()
        val arity = ALLOWED_FUNCTIONS[upperName]
            ?: throw FilterDslParseException(
                FilterDslParseError(
                    "Function '$name' is not allowed; permitted functions: ${ALLOWED_FUNCTIONS.keys.sorted().joinToString(", ")}",
                    name,
                    pos,
                )
            )

        s.advance() // consume '('
        val args = mutableListOf<ValueExpr>()
        if (!s.isAtEnd() && s.peek().type != TokenType.RPAREN) {
            args += parseValueExpr(s)
            while (s.matchType(TokenType.COMMA) != null) {
                args += parseValueExpr(s)
            }
        }
        s.expect(TokenType.RPAREN, "')'")

        if (args.size !in arity) {
            val expected = if (arity.first == arity.last) "${arity.first}" else "${arity.first}–${arity.last}"
            throw FilterDslParseException(
                FilterDslParseError(
                    "Function '$upperName' expects $expected argument(s) but got ${args.size}",
                    upperName,
                    pos,
                )
            )
        }

        return ValueExpr.FunctionCall(upperName, args, pos)
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
 * Returns `null` if [rawFilter] is null or blank.
 *
 * @throws FilterParseException if the DSL parse fails
 */
fun parseFilter(rawFilter: String?): ParsedFilter? {
    val trimmed = rawFilter?.takeIf { it.isNotBlank() } ?: return null
    return when (val result = FilterDslParser.parse(trimmed)) {
        is FilterDslParseResult.Success -> ParsedFilter(
            expr = result.expr,
            canonical = FilterDslTranslator.canonicalize(result.expr),
        )
        is FilterDslParseResult.Failure -> throw FilterParseException(result.error)
    }
}
