package dev.dmigrate.cli.commands

internal class FilterDslParserState(private val tokens: List<Token>) {
    private var pos = 0

    fun isAtEnd(): Boolean = pos >= tokens.size
    fun peek(): Token = tokens[pos]
    fun advance(): Token = tokens[pos++]
    fun save(): Int = pos
    fun restore(saved: Int) {
        pos = saved
    }

    fun matchKeyword(vararg keywords: String): Token? {
        if (isAtEnd()) return null
        val token = peek()
        if (token.type == TokenType.KEYWORD && token.text in keywords) {
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
            val lastPos = if (tokens.isNotEmpty()) {
                tokens.last().pos + tokens.last().text.length
            } else {
                0
            }
            throw FilterDslParseException(
                FilterDslParseError("Expected $description but reached end of input", null, lastPos),
            )
        }
        val token = peek()
        if (token.type != type) {
            throw error("Expected $description but found '${token.text}'", token)
        }
        return advance()
    }

    fun expectKeyword(keyword: String): Token {
        if (isAtEnd()) {
            val lastPos = if (tokens.isNotEmpty()) {
                tokens.last().pos + tokens.last().text.length
            } else {
                0
            }
            throw FilterDslParseException(
                FilterDslParseError("Expected '$keyword' but reached end of input", null, lastPos),
            )
        }
        val token = peek()
        if (token.type != TokenType.KEYWORD || token.text != keyword) {
            throw error("Expected '$keyword' but found '${token.text}'", token)
        }
        return advance()
    }

    fun error(message: String, token: Token): FilterDslParseException =
        FilterDslParseException(FilterDslParseError(message, token.text, token.pos))
}
