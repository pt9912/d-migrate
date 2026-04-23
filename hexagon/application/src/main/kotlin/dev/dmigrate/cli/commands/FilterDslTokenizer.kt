package dev.dmigrate.cli.commands

/**
 * Tokenizer for the d-migrate filter DSL.
 *
 * Converts a raw filter string into a flat list of [Token]s that the
 * [FilterDslParser] consumes via its recursive-descent parser.
 */
internal object FilterDslTokenizer {

    fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c in SINGLE_CHAR_TOKENS -> { tokens += Token(SINGLE_CHAR_TOKENS.getValue(c), c.toString(), i); i++ }
                c in ARITH_CHARS -> { tokens += Token(TokenType.ARITH, c.toString(), i); i++ }
                c == '=' || c == '!' || c == '>' || c == '<' -> {
                    val (tok, next) = tokenizeOperator(input, i); tokens += tok; i = next
                }
                c == '\'' -> { val (tok, next) = tokenizeString(input, i); tokens += tok; i = next }
                c.isDigit() -> { val (tok, next) = tokenizeNumber(input, i); tokens += tok; i = next }
                c.isLetter() || c == '_' -> { val (tok, next) = tokenizeWord(input, i); tokens += tok; i = next }
                else -> throw FilterDslParseException(
                    FilterDslParseError("Unexpected character '${c}'", c.toString(), i)
                )
            }
        }
        return tokens
    }

    private val KEYWORDS = setOf("AND", "OR", "NOT", "IN", "IS", "NULL")

    private val SINGLE_CHAR_TOKENS = mapOf(
        '(' to TokenType.LPAREN, ')' to TokenType.RPAREN,
        ',' to TokenType.COMMA, '.' to TokenType.DOT,
    )
    private val ARITH_CHARS = setOf('+', '-', '*', '/')

    private fun tokenizeOperator(input: String, pos: Int): Pair<Token, Int> {
        val c = input[pos]
        val hasNext = pos + 1 < input.length
        return when {
            c == '=' -> Token(TokenType.OP, "=", pos) to pos + 1
            c == '!' && hasNext && input[pos + 1] == '=' -> Token(TokenType.OP, "!=", pos) to pos + 2
            c == '>' && hasNext && input[pos + 1] == '=' -> Token(TokenType.OP, ">=", pos) to pos + 2
            c == '<' && hasNext && input[pos + 1] == '=' -> Token(TokenType.OP, "<=", pos) to pos + 2
            c == '>' -> Token(TokenType.OP, ">", pos) to pos + 1
            else -> Token(TokenType.OP, "<", pos) to pos + 1
        }
    }

    private fun tokenizeString(input: String, startPos: Int): Pair<Token, Int> {
        var i = startPos + 1
        val sb = StringBuilder()
        while (i < input.length) {
            if (input[i] == '\'') {
                if (i + 1 < input.length && input[i + 1] == '\'') { sb.append('\''); i += 2 }
                else break
            } else { sb.append(input[i]); i++ }
        }
        if (i >= input.length) {
            throw FilterDslParseException(FilterDslParseError("Unterminated string literal", null, startPos))
        }
        return Token(TokenType.STRING, sb.toString(), startPos) to (i + 1)
    }

    private fun tokenizeNumber(input: String, startPos: Int): Pair<Token, Int> {
        var i = startPos
        while (i < input.length && input[i].isDigit()) i++
        if (hasDecimalPart(input, i)) {
            i++
            while (i < input.length && input[i].isDigit()) i++
            val text = input.substring(startPos, i)
            val intPart = text.substringBefore('.')
            if (intPart.length > 1 && intPart.startsWith('0')) {
                throw FilterDslParseException(FilterDslParseError("Leading zeros not allowed in numeric literal '$text'", text, startPos))
            }
            return Token(TokenType.DECIMAL, text, startPos) to i
        }
        val text = input.substring(startPos, i)
        if (text.length > 1 && text.startsWith('0')) {
            throw FilterDslParseException(FilterDslParseError("Leading zeros not allowed in numeric literal '$text'", text, startPos))
        }
        return Token(TokenType.INTEGER, text, startPos) to i
    }

    private fun hasDecimalPart(input: String, index: Int): Boolean {
        if (index >= input.length || input[index] != '.') return false
        val nextIndex = index + 1
        return nextIndex < input.length && input[nextIndex].isDigit()
    }

    private fun tokenizeWord(input: String, startPos: Int): Pair<Token, Int> {
        var i = startPos
        while (i < input.length && (input[i].isLetterOrDigit() || input[i] == '_')) i++
        val text = input.substring(startPos, i)
        val upper = text.uppercase(java.util.Locale.ROOT)
        val token = when {
            upper in KEYWORDS -> Token(TokenType.KEYWORD, upper, startPos)
            upper == "TRUE" || upper == "FALSE" -> Token(TokenType.BOOL, upper, startPos)
            else -> Token(TokenType.IDENTIFIER, text, startPos)
        }
        return token to i
    }
}

// ── Shared token types ────────────────────────────────────────────

internal enum class TokenType {
    IDENTIFIER, INTEGER, DECIMAL, STRING, BOOL,
    KEYWORD, // AND, OR, NOT, IN, IS, NULL
    OP,      // = != > >= < <=
    ARITH,   // + - * /
    LPAREN, RPAREN, COMMA, DOT,
}

internal data class Token(
    val type: TokenType,
    val text: String,
    val pos: Int,
)
