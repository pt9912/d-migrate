package dev.dmigrate.driver

internal enum class ViewQueryTokenType {
    WORD, STRING, NUMBER, LPAREN, RPAREN, COMMA, OP, WS, OTHER,
}

internal data class ViewQueryToken(
    val type: ViewQueryTokenType,
    val text: String,
)

internal object ViewQueryTokenSupport {
    fun word(text: String) = ViewQueryToken(ViewQueryTokenType.WORD, text)
    fun ws() = ViewQueryToken(ViewQueryTokenType.WS, " ")
    fun lparen() = ViewQueryToken(ViewQueryTokenType.LPAREN, "(")
    fun rparen() = ViewQueryToken(ViewQueryTokenType.RPAREN, ")")
    fun comma() = ViewQueryToken(ViewQueryTokenType.COMMA, ",")
    fun string(text: String) = ViewQueryToken(ViewQueryTokenType.STRING, text)
    fun other(text: String) = ViewQueryToken(ViewQueryTokenType.OTHER, text)
}

internal object ViewQueryTokenizer {

    fun tokenize(sql: String): List<ViewQueryToken> {
        val tokens = mutableListOf<ViewQueryToken>()
        var index = 0
        while (index < sql.length) {
            val char = sql[index]
            when {
                char.isWhitespace() -> index = scanWhitespace(sql, index, tokens)
                char == '\'' -> index = scanStringLiteral(sql, index, tokens)
                char == '(' -> {
                    tokens += ViewQueryToken(ViewQueryTokenType.LPAREN, "(")
                    index++
                }
                char == ')' -> {
                    tokens += ViewQueryToken(ViewQueryTokenType.RPAREN, ")")
                    index++
                }
                char == ',' -> {
                    tokens += ViewQueryToken(ViewQueryTokenType.COMMA, ",")
                    index++
                }
                char.isLetter() || char == '_' -> index = scanWord(sql, index, tokens)
                char.isDigit() -> index = scanNumber(sql, index, tokens)
                char == '"' || char == '`' -> index = scanQuotedIdentifier(sql, index, tokens)
                else -> {
                    tokens += ViewQueryToken(ViewQueryTokenType.OTHER, char.toString())
                    index++
                }
            }
        }
        return tokens
    }

    fun render(tokens: List<ViewQueryToken>): String = tokens.joinToString("") { it.text }

    private fun scanWhitespace(
        sql: String,
        start: Int,
        tokens: MutableList<ViewQueryToken>,
    ): Int {
        var index = start
        while (index < sql.length && sql[index].isWhitespace()) index++
        tokens += ViewQueryToken(ViewQueryTokenType.WS, sql.substring(start, index))
        return index
    }

    private fun scanStringLiteral(
        sql: String,
        start: Int,
        tokens: MutableList<ViewQueryToken>,
    ): Int {
        var index = start + 1
        while (index < sql.length) {
            if (sql[index] == '\'' && index + 1 < sql.length && sql[index + 1] == '\'') {
                index += 2
            } else if (sql[index] == '\'') {
                index++
                break
            } else {
                index++
            }
        }
        tokens += ViewQueryToken(ViewQueryTokenType.STRING, sql.substring(start, index))
        return index
    }

    private fun scanWord(
        sql: String,
        start: Int,
        tokens: MutableList<ViewQueryToken>,
    ): Int {
        var index = start
        while (index < sql.length && (sql[index].isLetterOrDigit() || sql[index] == '_')) index++
        tokens += ViewQueryToken(ViewQueryTokenType.WORD, sql.substring(start, index))
        return index
    }

    private fun scanNumber(
        sql: String,
        start: Int,
        tokens: MutableList<ViewQueryToken>,
    ): Int {
        var index = start
        while (index < sql.length && sql[index].isDigit()) index++
        if (hasFractionalPart(sql, index)) {
            index++
            while (index < sql.length && sql[index].isDigit()) index++
        }
        tokens += ViewQueryToken(ViewQueryTokenType.NUMBER, sql.substring(start, index))
        return index
    }

    private fun scanQuotedIdentifier(
        sql: String,
        start: Int,
        tokens: MutableList<ViewQueryToken>,
    ): Int {
        val quote = sql[start]
        var index = start + 1
        while (index < sql.length && sql[index] != quote) index++
        if (index < sql.length) index++
        tokens += ViewQueryToken(ViewQueryTokenType.WORD, sql.substring(start, index))
        return index
    }

    private fun hasFractionalPart(sql: String, dotIndex: Int): Boolean =
        dotIndex < sql.length &&
            sql[dotIndex] == '.' &&
            dotIndex + 1 < sql.length &&
            sql[dotIndex + 1].isDigit()
}
