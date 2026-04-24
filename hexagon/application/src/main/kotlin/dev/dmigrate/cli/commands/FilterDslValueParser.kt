package dev.dmigrate.cli.commands

internal object FilterDslValueParser {
    private val ALLOWED_FUNCTIONS: Map<String, IntRange> = mapOf(
        "LOWER" to 1..1,
        "UPPER" to 1..1,
        "TRIM" to 1..1,
        "LENGTH" to 1..1,
        "ABS" to 1..1,
        "ROUND" to 1..2,
        "COALESCE" to 2..Int.MAX_VALUE,
    )

    fun parseValueExpr(state: FilterDslParserState): ValueExpr = parseAddExpr(state)

    private fun parseAddExpr(state: FilterDslParserState): ValueExpr {
        var left = parseMulExpr(state)
        while (!state.isAtEnd() &&
            state.peek().type == TokenType.ARITH &&
            state.peek().text in setOf("+", "-")
        ) {
            val op = state.advance()
            val right = parseMulExpr(state)
            left = ValueExpr.Arithmetic(left, op.text, right)
        }
        return left
    }

    private fun parseMulExpr(state: FilterDslParserState): ValueExpr {
        var left = parseUnaryExpr(state)
        while (!state.isAtEnd() &&
            state.peek().type == TokenType.ARITH &&
            state.peek().text in setOf("*", "/")
        ) {
            val op = state.advance()
            val right = parseUnaryExpr(state)
            left = ValueExpr.Arithmetic(left, op.text, right)
        }
        return left
    }

    private fun parseUnaryExpr(state: FilterDslParserState): ValueExpr {
        if (!state.isAtEnd() && state.peek().type == TokenType.ARITH && state.peek().text == "-") {
            state.advance()
            return ValueExpr.UnaryMinus(parseAtom(state))
        }
        return parseAtom(state)
    }

    private fun parseAtom(state: FilterDslParserState): ValueExpr {
        if (state.isAtEnd()) {
            throw FilterDslParseException(
                FilterDslParseError("Expected value but reached end of input", null, 0),
            )
        }
        val token = state.peek()
        return when (token.type) {
            TokenType.INTEGER -> {
                state.advance()
                ValueExpr.IntLiteral(token.text.toLong(), token.pos)
            }
            TokenType.DECIMAL -> {
                state.advance()
                ValueExpr.DecLiteral(token.text.toBigDecimal(), token.pos)
            }
            TokenType.STRING -> {
                state.advance()
                ValueExpr.StrLiteral(token.text, token.pos)
            }
            TokenType.BOOL -> {
                state.advance()
                ValueExpr.BoolLiteral(token.text.uppercase(java.util.Locale.ROOT) == "TRUE", token.pos)
            }
            TokenType.KEYWORD -> {
                if (token.text == "NULL") {
                    state.advance()
                    ValueExpr.NullKeyword(token.pos)
                } else {
                    throw state.error("Unexpected keyword '${token.text}' in value position", token)
                }
            }
            TokenType.IDENTIFIER -> parseIdentifierLike(state, token)
            TokenType.LPAREN -> {
                state.advance()
                val inner = parseValueExpr(state)
                state.expect(TokenType.RPAREN, "')'")
                ValueExpr.GroupedValue(inner)
            }
            else -> throw state.error("Expected value but found '${token.text}'", token)
        }
    }

    private fun parseIdentifierLike(state: FilterDslParserState, token: Token): ValueExpr {
        state.advance()
        if (!state.isAtEnd() && state.peek().type == TokenType.LPAREN) {
            return parseFunctionCall(state, token.text, token.pos)
        }
        if (!state.isAtEnd() && state.peek().type == TokenType.DOT) {
            state.advance()
            val column = state.expect(TokenType.IDENTIFIER, "column name after '.'")
            if (!state.isAtEnd() && state.peek().type == TokenType.DOT) {
                throw state.error(
                    "Multi-level qualified identifiers (schema.table.column) are not supported; use table.column",
                    state.peek(),
                )
            }
            return ValueExpr.Identifier("${token.text}.${column.text}", token.pos)
        }
        return ValueExpr.Identifier(token.text, token.pos)
    }

    private fun parseFunctionCall(
        state: FilterDslParserState,
        name: String,
        pos: Int,
    ): ValueExpr {
        val upperName = name.uppercase(java.util.Locale.ROOT)
        val arity = ALLOWED_FUNCTIONS[upperName]
            ?: throw FilterDslParseException(
                FilterDslParseError(
                    "Function '$name' is not allowed; permitted functions: ${ALLOWED_FUNCTIONS.keys.sorted().joinToString(", ")}",
                    name,
                    pos,
                ),
            )

        state.advance()
        val args = mutableListOf<ValueExpr>()
        if (!state.isAtEnd() && state.peek().type != TokenType.RPAREN) {
            args += parseValueExpr(state)
            while (state.matchType(TokenType.COMMA) != null) {
                args += parseValueExpr(state)
            }
        }
        state.expect(TokenType.RPAREN, "')'")

        if (args.size !in arity) {
            val expected = if (arity.first == arity.last) "${arity.first}" else "${arity.first}–${arity.last}"
            throw FilterDslParseException(
                FilterDslParseError(
                    "Function '$upperName' expects $expected argument(s) but got ${args.size}",
                    upperName,
                    pos,
                ),
            )
        }

        return ValueExpr.FunctionCall(upperName, args, pos)
    }
}
