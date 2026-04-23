package dev.dmigrate.driver

internal interface ViewQueryRule {
    fun apply(tokens: List<ViewQueryToken>): List<ViewQueryToken>
}

internal class WordReplaceRule(
    private val from: String,
    private val to: String,
) : ViewQueryRule {
    override fun apply(tokens: List<ViewQueryToken>): List<ViewQueryToken> {
        val result = mutableListOf<ViewQueryToken>()
        for ((index, token) in tokens.withIndex()) {
            if (token.type == ViewQueryTokenType.WORD && token.text.equals(from, ignoreCase = true)) {
                val next = tokens.drop(index + 1).firstOrNull { it.type != ViewQueryTokenType.WS }
                if (next?.type != ViewQueryTokenType.LPAREN) {
                    result += ViewQueryToken(ViewQueryTokenType.WORD, to)
                    continue
                }
            }
            result += token
        }
        return result
    }
}

internal class FuncReplaceRule(
    private val fromName: String,
    private val transform: (name: String, args: List<List<ViewQueryToken>>) -> List<ViewQueryToken>,
) : ViewQueryRule {
    override fun apply(tokens: List<ViewQueryToken>): List<ViewQueryToken> {
        val result = mutableListOf<ViewQueryToken>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            if (token.type == ViewQueryTokenType.WORD && token.text.equals(fromName, ignoreCase = true)) {
                val parenIndex = (index + 1 until tokens.size).firstOrNull { tokens[it].type != ViewQueryTokenType.WS }
                if (parenIndex != null && tokens[parenIndex].type == ViewQueryTokenType.LPAREN) {
                    val (args, endIndex) = ViewQueryRuleSupport.extractArgs(tokens, parenIndex)
                    if (endIndex >= 0) {
                        result += transform(token.text, args)
                        index = endIndex + 1
                        continue
                    }
                }
            }
            result += token
            index++
        }
        return result
    }
}

internal class ExtractReplaceRule(
    private val unit: String,
    private val transform: (expr: List<ViewQueryToken>) -> List<ViewQueryToken>,
) : ViewQueryRule {
    override fun apply(tokens: List<ViewQueryToken>): List<ViewQueryToken> {
        val result = mutableListOf<ViewQueryToken>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val replaced = tryReplaceExtract(tokens, index, token)
            if (replaced != null) {
                result += replaced.first
                index = replaced.second
                continue
            }
            result += token
            index++
        }
        return result
    }

    private fun tryReplaceExtract(
        tokens: List<ViewQueryToken>,
        index: Int,
        token: ViewQueryToken,
    ): Pair<List<ViewQueryToken>, Int>? {
        if (token.type != ViewQueryTokenType.WORD || !token.text.equals("EXTRACT", ignoreCase = true)) return null
        val parenIndex = (index + 1 until tokens.size).firstOrNull { tokens[it].type != ViewQueryTokenType.WS } ?: return null
        if (tokens[parenIndex].type != ViewQueryTokenType.LPAREN) return null
        val (innerTokens, endIndex) = ViewQueryRuleSupport.extractInnerTokens(tokens, parenIndex) ?: return null
        val words = innerTokens.filter { it.type == ViewQueryTokenType.WORD }
        if (!matchesExtractPrefix(words)) return null
        val fromIndex = innerTokens.indexOfFirst {
            it.type == ViewQueryTokenType.WORD && it.text.equals("FROM", ignoreCase = true)
        }
        val expr = innerTokens.drop(fromIndex + 1).dropWhile { it.type == ViewQueryTokenType.WS }
        return transform(expr) to (endIndex + 1)
    }

    private fun matchesExtractPrefix(words: List<ViewQueryToken>): Boolean =
        words.size >= 2 &&
            words[0].text.equals(unit, ignoreCase = true) &&
            words[1].text.equals("FROM", ignoreCase = true)
}

internal class SubstringReplaceRule(
    private val transform: (expr: List<ViewQueryToken>, from: String, length: String) -> List<ViewQueryToken>,
) : ViewQueryRule {
    override fun apply(tokens: List<ViewQueryToken>): List<ViewQueryToken> {
        val result = mutableListOf<ViewQueryToken>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            val replaced = tryReplaceSubstring(tokens, index, token)
            if (replaced != null) {
                result += replaced.first
                index = replaced.second
                continue
            }
            result += token
            index++
        }
        return result
    }

    private fun tryReplaceSubstring(
        tokens: List<ViewQueryToken>,
        index: Int,
        token: ViewQueryToken,
    ): Pair<List<ViewQueryToken>, Int>? {
        if (token.type != ViewQueryTokenType.WORD || !token.text.equals("SUBSTRING", ignoreCase = true)) return null
        val parenIndex = (index + 1 until tokens.size).firstOrNull { tokens[it].type != ViewQueryTokenType.WS } ?: return null
        if (tokens[parenIndex].type != ViewQueryTokenType.LPAREN) return null
        val (innerTokens, endIndex) = ViewQueryRuleSupport.extractInnerTokens(tokens, parenIndex) ?: return null
        val fromIndex = innerTokens.indexOfFirst {
            it.type == ViewQueryTokenType.WORD && it.text.equals("FROM", ignoreCase = true)
        }
        val forIndex = innerTokens.indexOfFirst {
            it.type == ViewQueryTokenType.WORD && it.text.equals("FOR", ignoreCase = true)
        }
        if (fromIndex < 0 || forIndex <= fromIndex) return null
        val expr = innerTokens.take(fromIndex).dropLastWhile { it.type == ViewQueryTokenType.WS }
        val fromValue = innerTokens.subList(fromIndex + 1, forIndex)
            .firstOrNull { it.type == ViewQueryTokenType.NUMBER }?.text ?: "1"
        val forValue = innerTokens.drop(forIndex + 1)
            .firstOrNull { it.type == ViewQueryTokenType.NUMBER }?.text ?: "1"
        return transform(expr, fromValue, forValue) to (endIndex + 1)
    }
}

internal object ViewQueryRuleSupport {
    fun extractArgs(
        tokens: List<ViewQueryToken>,
        lparenIndex: Int,
    ): Pair<List<List<ViewQueryToken>>, Int> {
        var depth = 0
        val args = mutableListOf<MutableList<ViewQueryToken>>()
        var current = mutableListOf<ViewQueryToken>()
        for (index in lparenIndex until tokens.size) {
            val token = tokens[index]
            when {
                token.type == ViewQueryTokenType.LPAREN -> {
                    depth++
                    if (depth > 1) current += token
                }
                token.type == ViewQueryTokenType.RPAREN -> {
                    depth--
                    if (depth == 0) {
                        if (current.isNotEmpty()) args += current
                        return args to index
                    }
                    current += token
                }
                token.type == ViewQueryTokenType.COMMA && depth == 1 -> {
                    args += current
                    current = mutableListOf()
                }
                depth >= 1 -> current += token
            }
        }
        return args to -1
    }

    fun extractInnerTokens(
        tokens: List<ViewQueryToken>,
        lparenIndex: Int,
    ): Pair<List<ViewQueryToken>, Int>? {
        var depth = 0
        val inner = mutableListOf<ViewQueryToken>()
        for (index in lparenIndex until tokens.size) {
            val token = tokens[index]
            when {
                token.type == ViewQueryTokenType.LPAREN -> {
                    depth++
                    if (depth > 1) inner += token
                }
                token.type == ViewQueryTokenType.RPAREN -> {
                    depth--
                    if (depth == 0) return inner to index
                    inner += token
                }
                else -> if (depth >= 1) inner += token
            }
        }
        return null
    }

    fun callWithArgs(name: String, args: List<List<ViewQueryToken>>): List<ViewQueryToken> =
        listOf(ViewQueryTokenSupport.word(name), ViewQueryTokenSupport.lparen()) +
            joinArgs(args) +
            listOf(ViewQueryTokenSupport.rparen())

    fun emptyFunctionCall(name: String): List<ViewQueryToken> =
        listOf(
            ViewQueryTokenSupport.word(name),
            ViewQueryTokenSupport.lparen(),
            ViewQueryTokenSupport.rparen(),
        )

    fun literalCall(name: String, literal: String): List<ViewQueryToken> =
        listOf(
            ViewQueryTokenSupport.word(name),
            ViewQueryTokenSupport.lparen(),
            ViewQueryTokenSupport.string(literal),
            ViewQueryTokenSupport.rparen(),
        )

    fun wrapCall(name: String, expr: List<ViewQueryToken>): List<ViewQueryToken> =
        listOf(ViewQueryTokenSupport.word(name), ViewQueryTokenSupport.lparen()) +
            expr +
            listOf(ViewQueryTokenSupport.rparen())

    fun functionCall(
        name: String,
        expr: List<ViewQueryToken>,
        literal: String,
    ): List<ViewQueryToken> =
        listOf(
            ViewQueryTokenSupport.word(name),
            ViewQueryTokenSupport.lparen(),
        ) + expr + listOf(
            ViewQueryTokenSupport.comma(),
            ViewQueryTokenSupport.ws(),
            ViewQueryTokenSupport.string(literal),
            ViewQueryTokenSupport.rparen(),
        )

    fun originalDateTrunc(args: List<List<ViewQueryToken>>): List<ViewQueryToken> =
        callWithArgs("DATE_TRUNC", args)

    fun substringCall(
        name: String,
        expr: List<ViewQueryToken>,
        from: String,
        length: String,
    ): List<ViewQueryToken> =
        listOf(ViewQueryTokenSupport.word(name), ViewQueryTokenSupport.lparen()) +
            expr +
            listOf(
                ViewQueryTokenSupport.comma(),
                ViewQueryTokenSupport.ws(),
                ViewQueryTokenSupport.word(from),
                ViewQueryTokenSupport.comma(),
                ViewQueryTokenSupport.ws(),
                ViewQueryTokenSupport.word(length),
                ViewQueryTokenSupport.rparen(),
            )

    fun castStrftimeInt(
        pattern: String,
        expr: List<ViewQueryToken>,
    ): List<ViewQueryToken> =
        listOf(
            ViewQueryTokenSupport.word("CAST"),
            ViewQueryTokenSupport.lparen(),
            ViewQueryTokenSupport.word("strftime"),
            ViewQueryTokenSupport.lparen(),
            ViewQueryTokenSupport.string(pattern),
            ViewQueryTokenSupport.comma(),
            ViewQueryTokenSupport.ws(),
        ) + expr + listOf(
            ViewQueryTokenSupport.rparen(),
            ViewQueryTokenSupport.ws(),
            ViewQueryTokenSupport.word("AS"),
            ViewQueryTokenSupport.ws(),
            ViewQueryTokenSupport.word("INTEGER"),
            ViewQueryTokenSupport.rparen(),
        )

    private fun joinArgs(args: List<List<ViewQueryToken>>): List<ViewQueryToken> =
        args.flatMapIndexed { index, arg ->
            if (index > 0) {
                listOf(ViewQueryTokenSupport.comma(), ViewQueryTokenSupport.ws()) + arg
            } else {
                arg
            }
        }
}
