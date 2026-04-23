package dev.dmigrate.driver

/**
 * Transforms SQL functions in view queries between dialects.
 *
 * Uses a token-based approach: the query is first split into tokens
 * (keywords, identifiers, string literals, operators, parentheses),
 * then transformations are applied on the token stream. This avoids
 * the false positives of pure regex (e.g. matching inside string
 * literals or partial identifier matches).
 */
class ViewQueryTransformer(private val targetDialect: DatabaseDialect) {

    fun transform(query: String, sourceDialect: String?): Pair<String, List<TransformationNote>> {
        val notes = mutableListOf<TransformationNote>()
        val tokens = tokenize(query)
        val transformed = applyRules(tokens)
        val result = render(transformed)

        // Check for potentially untransformed dialect-specific functions
        if (sourceDialect != null && sourceDialect != targetDialect.name.lowercase()) {
            val unknownFunctions = detectUnknownFunctions(transformed)
            if (unknownFunctions.isNotEmpty()) {
                notes += TransformationNote(
                    type = NoteType.WARNING,
                    code = "W111",
                    objectName = "view_query",
                    message = "View query may contain dialect-specific functions: ${unknownFunctions.joinToString(", ")}",
                    hint = "Review and manually adjust if needed."
                )
            }
        }

        return result to notes
    }

    // ── Tokenizer ──────────────────────────────────────────────

    private enum class TType { WORD, STRING, NUMBER, LPAREN, RPAREN, COMMA, OP, WS, OTHER }

    private data class Token(val type: TType, val text: String)

    private fun tokenize(sql: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i = scanWhitespace(sql, i, tokens)
                c == '\'' -> i = scanStringLiteral(sql, i, tokens)
                c == '(' -> { tokens += Token(TType.LPAREN, "("); i++ }
                c == ')' -> { tokens += Token(TType.RPAREN, ")"); i++ }
                c == ',' -> { tokens += Token(TType.COMMA, ","); i++ }
                c.isLetter() || c == '_' -> i = scanWord(sql, i, tokens)
                c.isDigit() -> i = scanNumber(sql, i, tokens)
                c == '"' || c == '`' -> i = scanQuotedIdentifier(sql, i, tokens)
                else -> { tokens += Token(TType.OTHER, c.toString()); i++ }
            }
        }
        return tokens
    }

    private fun scanWhitespace(sql: String, start: Int, tokens: MutableList<Token>): Int {
        var i = start
        while (i < sql.length && sql[i].isWhitespace()) i++
        tokens += Token(TType.WS, sql.substring(start, i))
        return i
    }

    private fun scanStringLiteral(sql: String, start: Int, tokens: MutableList<Token>): Int {
        var i = start + 1
        while (i < sql.length) {
            if (sql[i] == '\'' && i + 1 < sql.length && sql[i + 1] == '\'') { i += 2 }
            else if (sql[i] == '\'') { i++; break }
            else i++
        }
        tokens += Token(TType.STRING, sql.substring(start, i))
        return i
    }

    private fun scanWord(sql: String, start: Int, tokens: MutableList<Token>): Int {
        var i = start
        while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
        tokens += Token(TType.WORD, sql.substring(start, i))
        return i
    }

    private fun scanNumber(sql: String, start: Int, tokens: MutableList<Token>): Int {
        var i = start
        while (i < sql.length && sql[i].isDigit()) i++
        if (hasFractionalPart(sql, i)) {
            i++
            while (i < sql.length && sql[i].isDigit()) i++
        }
        tokens += Token(TType.NUMBER, sql.substring(start, i))
        return i
    }

    private fun scanQuotedIdentifier(sql: String, start: Int, tokens: MutableList<Token>): Int {
        val quote = sql[start]
        var i = start + 1
        while (i < sql.length && sql[i] != quote) i++
        if (i < sql.length) i++
        tokens += Token(TType.WORD, sql.substring(start, i))
        return i
    }

    private fun render(tokens: List<Token>): String = tokens.joinToString("") { it.text }

    // ── Token-based transformation ─────────────────────────────

    private fun applyRules(tokens: List<Token>): List<Token> {
        var result = tokens.toMutableList()
        for (rule in getRules()) {
            result = rule.apply(result).toMutableList()
        }
        return result
    }

    private interface Rule {
        fun apply(tokens: List<Token>): List<Token>
    }

    /** Replaces a standalone WORD token (not followed by '(') */
    private class WordReplace(val from: String, val to: String) : Rule {
        override fun apply(tokens: List<Token>): List<Token> {
            val result = mutableListOf<Token>()
            for ((idx, tok) in tokens.withIndex()) {
                if (tok.type == TType.WORD && tok.text.equals(from, ignoreCase = true)) {
                    // Only replace if NOT followed by '(' (not a function call)
                    val next = tokens.drop(idx + 1).firstOrNull { it.type != TType.WS }
                    if (next?.type != TType.LPAREN) {
                        result += Token(TType.WORD, to)
                        continue
                    }
                }
                result += tok
            }
            return result
        }
    }

    /** Replaces a function call FUNC_NAME(...) with a transformed version */
    private class FuncReplace(
        val fromName: String,
        val transform: (name: String, args: List<List<Token>>) -> List<Token>,
    ) : Rule {
        override fun apply(tokens: List<Token>): List<Token> {
            val result = mutableListOf<Token>()
            var i = 0
            while (i < tokens.size) {
                val tok = tokens[i]
                if (tok.type == TType.WORD && tok.text.equals(fromName, ignoreCase = true)) {
                    // Look ahead for '('
                    val parenIdx = (i + 1 until tokens.size).firstOrNull { tokens[it].type != TType.WS }
                    if (parenIdx != null && tokens[parenIdx].type == TType.LPAREN) {
                        val (args, endIdx) = extractArgs(tokens, parenIdx)
                        if (endIdx >= 0) {
                            result += transform(tok.text, args)
                            i = endIdx + 1
                            continue
                        }
                    }
                }
                result += tok
                i++
            }
            return result
        }
    }

    /** Replaces EXTRACT(UNIT FROM expr) with dialect-specific form */
    private class ExtractReplace(
        val unit: String,
        val transform: (expr: List<Token>) -> List<Token>,
    ) : Rule {
        override fun apply(tokens: List<Token>): List<Token> {
            val result = mutableListOf<Token>()
            var i = 0
            while (i < tokens.size) {
                val tok = tokens[i]
                val replaced = tryReplaceExtract(tokens, i, tok)
                if (replaced != null) {
                    result += replaced.first
                    i = replaced.second
                    continue
                }
                result += tok
                i++
            }
            return result
        }

        private fun tryReplaceExtract(tokens: List<Token>, i: Int, tok: Token): Pair<List<Token>, Int>? {
            if (tok.type != TType.WORD || !tok.text.equals("EXTRACT", ignoreCase = true)) return null
            val parenIdx = (i + 1 until tokens.size).firstOrNull { tokens[it].type != TType.WS } ?: return null
            if (tokens[parenIdx].type != TType.LPAREN) return null
            val (innerTokens, endIdx) = extractInnerTokens(tokens, parenIdx) ?: return null
            val words = innerTokens.filter { it.type == TType.WORD }
            if (!matchesExtractPrefix(words)) return null
            val fromIdx = innerTokens.indexOfFirst { it.type == TType.WORD && it.text.equals("FROM", ignoreCase = true) }
            val expr = innerTokens.drop(fromIdx + 1).dropWhile { it.type == TType.WS }
            return transform(expr) to (endIdx + 1)
        }

        private fun matchesExtractPrefix(words: List<Token>): Boolean =
            words.size >= 3 &&
                words[0].text.equals(unit, ignoreCase = true) &&
                words[1].text.equals("FROM", ignoreCase = true)
    }

    /** Replaces SUBSTRING(expr FROM n FOR m) with dialect-specific form */
    private class SubstringReplace(
        val transform: (expr: List<Token>, from: String, length: String) -> List<Token>,
    ) : Rule {
        override fun apply(tokens: List<Token>): List<Token> {
            val result = mutableListOf<Token>()
            var i = 0
            while (i < tokens.size) {
                val tok = tokens[i]
                val replaced = tryReplaceSubstring(tokens, i, tok)
                if (replaced != null) {
                    result += replaced.first
                    i = replaced.second
                    continue
                }
                result += tok
                i++
            }
            return result
        }

        private fun tryReplaceSubstring(tokens: List<Token>, i: Int, tok: Token): Pair<List<Token>, Int>? {
            if (tok.type != TType.WORD || !tok.text.equals("SUBSTRING", ignoreCase = true)) return null
            val parenIdx = (i + 1 until tokens.size).firstOrNull { tokens[it].type != TType.WS } ?: return null
            if (tokens[parenIdx].type != TType.LPAREN) return null
            val (innerTokens, endIdx) = extractInnerTokens(tokens, parenIdx) ?: return null
            val fromIdx = innerTokens.indexOfFirst { it.type == TType.WORD && it.text.equals("FROM", ignoreCase = true) }
            val forIdx = innerTokens.indexOfFirst { it.type == TType.WORD && it.text.equals("FOR", ignoreCase = true) }
            if (fromIdx < 0 || forIdx <= fromIdx) return null
            val expr = innerTokens.take(fromIdx).dropLastWhile { it.type == TType.WS }
            val fromVal = innerTokens.subList(fromIdx + 1, forIdx).firstOrNull { it.type == TType.NUMBER }?.text ?: "1"
            val forVal = innerTokens.drop(forIdx + 1).firstOrNull { it.type == TType.NUMBER }?.text ?: "1"
            return transform(expr, fromVal, forVal) to (endIdx + 1)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    companion object {
        /** Extracts comma-separated args from tokens starting at LPAREN index */
        private fun extractArgs(tokens: List<Token>, lparenIdx: Int): Pair<List<List<Token>>, Int> {
            var depth = 0
            val args = mutableListOf<MutableList<Token>>()
            var current = mutableListOf<Token>()
            for (j in lparenIdx until tokens.size) {
                val t = tokens[j]
                when {
                    t.type == TType.LPAREN -> {
                        depth++
                        if (depth > 1) current += t
                    }
                    t.type == TType.RPAREN -> {
                        depth--
                        if (depth == 0) {
                            if (current.isNotEmpty()) args += current
                            return args to j
                        }
                        current += t
                    }
                    t.type == TType.COMMA && depth == 1 -> {
                        args += current
                        current = mutableListOf()
                    }
                    depth >= 1 -> current += t
                }
            }
            return args to -1
        }

        /** Extracts all tokens between matching parens (excluding the parens) */
        private fun extractInnerTokens(tokens: List<Token>, lparenIdx: Int): Pair<List<Token>, Int>? {
            var depth = 0
            val inner = mutableListOf<Token>()
            for (j in lparenIdx until tokens.size) {
                val t = tokens[j]
                when {
                    t.type == TType.LPAREN -> {
                        depth++
                        if (depth > 1) inner += t
                    }
                    t.type == TType.RPAREN -> {
                        depth--
                        if (depth == 0) return inner to j
                        inner += t
                    }
                    else -> if (depth >= 1) inner += t
                }
            }
            return null
        }

        private fun word(text: String) = Token(TType.WORD, text)
        private fun ws() = Token(TType.WS, " ")
        private fun lparen() = Token(TType.LPAREN, "(")
        private fun rparen() = Token(TType.RPAREN, ")")
        private fun comma() = Token(TType.COMMA, ",")
        private fun str(text: String) = Token(TType.STRING, text)
        private fun other(text: String) = Token(TType.OTHER, text)

        private fun hasFractionalPart(sql: String, dotIndex: Int): Boolean =
            dotIndex < sql.length &&
                sql[dotIndex] == '.' &&
                dotIndex + 1 < sql.length &&
                sql[dotIndex + 1].isDigit()

        private fun joinArgs(args: List<List<Token>>): List<Token> =
            args.flatMapIndexed { index, arg ->
                if (index > 0) listOf(comma(), ws()) + arg else arg
            }

        private fun originalDateTrunc(args: List<List<Token>>): List<Token> =
            listOf(word("DATE_TRUNC"), lparen()) + joinArgs(args) + listOf(rparen())

        private fun substringCall(name: String, expr: List<Token>, from: String, len: String): List<Token> =
            listOf(word(name), lparen()) +
                expr +
                listOf(comma(), ws(), word(from), comma(), ws(), word(len), rparen())

        private fun castStrftimeInt(pattern: String, expr: List<Token>): List<Token> =
            listOf(
                word("CAST"),
                lparen(),
                word("strftime"),
                lparen(),
                str(pattern),
                comma(),
                ws(),
            ) +
                expr +
                listOf(rparen(), ws(), word("AS"), ws(), word("INTEGER"), rparen())
    }

    // ── Dialect rules ──────────────────────────────────────────

    private fun getRules(): List<Rule> = when (targetDialect) {
        DatabaseDialect.MYSQL -> mysqlRules
        DatabaseDialect.SQLITE -> sqliteRules
        DatabaseDialect.POSTGRESQL -> postgresRules
    }

    private val mysqlRules: List<Rule> = listOf(
        // DATE_TRUNC('month', col) → DATE_FORMAT(col, '%Y-%m-01')
        FuncReplace("DATE_TRUNC") { _, args ->
            if (args.size == 2) {
                val unit = args[0].firstOrNull { it.type == TType.STRING }?.text?.removeSurrounding("'")
                val col = args[1].dropWhile { it.type == TType.WS }
                when (unit) {
                    "month" -> listOf(word("DATE_FORMAT"), lparen()) +
                        col +
                        listOf(comma(), ws(), str("'%Y-%m-01'"), rparen())
                    "year" -> listOf(word("DATE_FORMAT"), lparen()) +
                        col +
                        listOf(comma(), ws(), str("'%Y-01-01'"), rparen())
                    "day" -> listOf(word("DATE"), lparen()) + col + listOf(rparen())
                    else -> originalDateTrunc(args)
                }
            } else listOf(word("DATE_TRUNC"), lparen(), rparen())
        },
        ExtractReplace("YEAR") { expr -> listOf(word("YEAR"), lparen()) + expr + listOf(rparen()) },
        ExtractReplace("MONTH") { expr -> listOf(word("MONTH"), lparen()) + expr + listOf(rparen()) },
        SubstringReplace { expr, from, len ->
            substringCall("SUBSTRING", expr, from, len)
        },
        FuncReplace("LENGTH") { _, args ->
            listOf(word("CHAR_LENGTH"), lparen()) +
                joinArgs(args) +
                listOf(rparen())
        },
        WordReplace("CURRENT_DATE", "CURDATE()"),
        WordReplace("CURRENT_TIME", "CURTIME()"),
        WordReplace("TRUE", "1"),
        WordReplace("FALSE", "0"),
    )

    private val sqliteRules: List<Rule> = listOf(
        FuncReplace("NOW") { _, _ -> listOf(word("datetime"), lparen(), str("'now'"), rparen()) },
        WordReplace("CURRENT_TIMESTAMP", "datetime('now')"),
        WordReplace("CURRENT_DATE", "date('now')"),
        WordReplace("CURRENT_TIME", "time('now')"),
        FuncReplace("DATE_TRUNC") { _, args ->
            if (args.size == 2) {
                val unit = args[0].firstOrNull { it.type == TType.STRING }?.text?.removeSurrounding("'")
                val col = args[1].dropWhile { it.type == TType.WS }
                when (unit) {
                    "month" -> listOf(word("strftime"), lparen(), str("'%Y-%m-01'"), comma(), ws()) +
                        col +
                        listOf(rparen())
                    "year" -> listOf(word("strftime"), lparen(), str("'%Y-01-01'"), comma(), ws()) +
                        col +
                        listOf(rparen())
                    "day" -> listOf(word("date"), lparen()) + col + listOf(rparen())
                    else -> originalDateTrunc(args)
                }
            } else listOf(word("DATE_TRUNC"), lparen(), rparen())
        },
        ExtractReplace("YEAR") { expr ->
            castStrftimeInt("'%Y'", expr)
        },
        ExtractReplace("MONTH") { expr ->
            castStrftimeInt("'%m'", expr)
        },
        FuncReplace("CONCAT") { _, args ->
            if (args.size >= 2) {
                args.flatMapIndexed { i, a ->
                    if (i > 0) listOf(ws(), other("||"), ws()) + a.dropWhile { it.type == TType.WS }
                    else a.dropWhile { it.type == TType.WS }
                }
            } else listOf(word("CONCAT"), lparen(), rparen())
        },
        SubstringReplace { expr, from, len ->
            substringCall("SUBSTR", expr, from, len)
        },
        WordReplace("TRUE", "1"),
        WordReplace("FALSE", "0"),
    )

    private val postgresRules: List<Rule> = listOf(
        FuncReplace("NOW") { _, _ -> listOf(word("CURRENT_TIMESTAMP")) },
    )

    // ── Unknown function detection ─────────────────────────────

    private val transparentFunctions = setOf(
        "COUNT", "SUM", "AVG", "MIN", "MAX", "ABS", "ROUND", "UPPER", "LOWER",
        "TRIM", "REPLACE", "COALESCE", "NULLIF", "CAST",
    )

    private val sqlKeywords = setOf(
        "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER",
        "ON", "AS", "AND", "OR", "NOT", "IN", "BETWEEN", "LIKE", "IS", "NULL",
        "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "DISTINCT",
        "UNION", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END",
        "ASC", "DESC", "FOR", "EACH", "ROW", "STATEMENT",
        "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
        "CREATE", "TABLE", "VIEW", "INDEX", "WITH",
        "PRIMARY", "KEY", "FOREIGN", "REFERENCES", "CONSTRAINT", "CHECK",
        "DEFAULT", "UNIQUE", "AUTO_INCREMENT", "AUTOINCREMENT",
        "INTEGER", "INT", "TEXT", "REAL", "BLOB", "VARCHAR", "CHAR",
        "BOOLEAN", "DECIMAL", "FLOAT", "DOUBLE", "DATE", "TIME", "TIMESTAMP",
        "SERIAL", "BIGINT", "SMALLINT", "TINYINT", "JSON", "JSONB", "UUID",
        "TRUE", "FALSE", "ALL", "ANY", "SOME",
        // Functions known from rules
        "NOW", "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME",
        "DATE_TRUNC", "EXTRACT", "SUBSTRING", "LENGTH", "CHAR_LENGTH",
        "CONCAT", "YEAR", "MONTH", "DATE", "DATE_FORMAT", "CURDATE", "CURTIME",
        "STRFTIME", "SUBSTR", "DATETIME",
    )

    private val allKnown = transparentFunctions + sqlKeywords

    private fun detectUnknownFunctions(tokens: List<Token>): List<String> {
        val unknown = mutableListOf<String>()
        for ((idx, tok) in tokens.withIndex()) {
            if (tok.type != TType.WORD) continue
            // Check if followed by '(' (skip whitespace)
            val next = tokens.drop(idx + 1).firstOrNull { it.type != TType.WS }
            if (next?.type == TType.LPAREN && tok.text.uppercase() !in allKnown) {
                unknown += tok.text.uppercase()
            }
        }
        return unknown.distinct()
    }
}
