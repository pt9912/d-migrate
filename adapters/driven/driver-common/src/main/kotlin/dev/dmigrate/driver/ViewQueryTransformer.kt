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

    /** Result of [assessPortability]: whether a view body is safe to emit verbatim. */
    data class ViewPortability(val portable: Boolean, val reason: String?)

    /**
     * Verdict on whether a view body can be emitted for [targetDialect] verbatim.
     * d-migrate does not transpile view bodies across dialects (no SQL transpiler
     * in 0.9.x), so a body carrying foreign identifier quoting or — for a
     * cross-dialect source — dialect-specific functions is reported as
     * non-portable. Callers skip such views with an E053 note instead of emitting
     * DDL the target rejects (e.g. MySQL backticks or `group_concat` into PG).
     */
    fun assessPortability(query: String, sourceDialect: String?): ViewPortability {
        // Normalise via DatabaseDialect so aliases ("postgres"/"pg"/"maria"/…) are
        // not mistaken for a foreign dialect; unparseable values stay conservative.
        val crossDialect = sourceDialect != null &&
            runCatching { DatabaseDialect.fromString(sourceDialect) }.getOrNull() != targetDialect
        val tokens = ViewQueryTokenizer.tokenize(query)
        val markers = mutableListOf<String>()
        // Backticks are MySQL-only quoting and are a hard syntax error in PG/SQLite.
        // Checked on the token stream so a backtick inside a string literal is ignored.
        if (targetDialect != DatabaseDialect.MYSQL &&
            tokens.any { it.type == ViewQueryTokenType.WORD && it.text.startsWith("`") }
        ) {
            markers += "MySQL-style backtick quoting"
        }
        // N4: PostgreSQL/SQLite cast `::` and concat `||` are not portable to MySQL.
        // Checked on a code-only view (string literals blanked) so `::`/`||` inside
        // a literal is ignored. `::` is always a syntax error in MySQL; `||` is
        // valid there as logical OR, so it is only flagged for a cross-dialect body.
        if (targetDialect == DatabaseDialect.MYSQL) {
            val codeOnly = tokens.joinToString("") {
                if (it.type == ViewQueryTokenType.STRING) " " else it.text
            }
            if (codeOnly.contains("::")) markers += "PostgreSQL-style cast (::)"
            if (crossDialect && codeOnly.contains("||")) markers += "PostgreSQL/SQLite-style concatenation (||)"
        }
        // T-SQL kennt weder `::` noch `||` (String-Verkettung ist `+`) noch
        // eine `LIMIT`-Klausel (`TOP`/`OFFSET … FETCH`); alle drei sind harte
        // Syntaxfehler, unabhängig vom Quelldialekt.
        if (targetDialect == DatabaseDialect.MSSQL) {
            val codeOnly = tokens.joinToString("") {
                if (it.type == ViewQueryTokenType.STRING) " " else it.text
            }
            if (codeOnly.contains("::")) markers += "PostgreSQL-style cast (::)"
            if (codeOnly.contains("||")) markers += "PostgreSQL/SQLite-style concatenation (||)"
            if (hasLimitClause(tokens)) markers += "LIMIT clause (T-SQL uses TOP / OFFSET … FETCH)"
            if (hasBareTopLevelOrderBy(tokens)) {
                markers += "ORDER BY in a view body without TOP/OFFSET (SQL Server Msg 1033)"
            }
        }
        // Umgekehrt ist T-SQL-Klammer-Quoting (`[dbo].[users]`) in keinem
        // anderen Dialekt gültig — ein mssql-stämmiger Body mit Klammern
        // (außerhalb von Literalen) ist dort nicht portabel.
        if (targetDialect != DatabaseDialect.MSSQL && sourceIs(sourceDialect, DatabaseDialect.MSSQL)) {
            val codeOnly = tokens.joinToString("") {
                if (it.type == ViewQueryTokenType.STRING) " " else it.text
            }
            if (codeOnly.contains("[")) markers += "T-SQL bracket quoting"
        }
        if (crossDialect) {
            val unknown = detectUnknownFunctions(applyRules(tokens))
            if (unknown.isNotEmpty()) {
                markers += "dialect-specific function(s): ${unknown.joinToString(", ")}"
            }
        }
        return ViewPortability(portable = markers.isEmpty(), reason = markers.joinToString("; ").ifEmpty { null })
    }

    private fun sourceIs(sourceDialect: String?, dialect: DatabaseDialect): Boolean =
        sourceDialect != null && runCatching { DatabaseDialect.fromString(sourceDialect) }.getOrNull() == dialect

    /**
     * Ein `ORDER BY` auf oberster Ebene ohne eine der Klauseln, die SQL Server
     * dafuer verlangt: PostgreSQL erlaubt es im View-Body, SQL Server lehnt die
     * Sicht sonst ab (Msg 1033). Ein `TOP 100 PERCENT` einzuschmuggeln waere
     * keine Loesung — SQL Server verwirft die Sortierung dann trotzdem, nur eben
     * unsichtbar.
     *
     * Nur Tiefe 0 zaehlt: `OVER (ORDER BY …)` und Unterabfragen stehen in
     * Klammern und bleiben unberuehrt.
     */
    private fun hasBareTopLevelOrderBy(tokens: List<ViewQueryToken>): Boolean {
        var depth = 0
        var orderByAtTopLevel = false
        var orderByPermitted = false
        for ((index, token) in tokens.withIndex()) {
            when {
                token.type == ViewQueryTokenType.LPAREN -> depth++
                token.type == ViewQueryTokenType.RPAREN -> depth--
                depth == 0 && token.type == ViewQueryTokenType.WORD -> {
                    if (permitsTopLevelOrderBy(tokens, index)) orderByPermitted = true
                    if (token.text.equals("ORDER", ignoreCase = true) &&
                        followingWord(tokens, index)?.equals("BY", ignoreCase = true) == true
                    ) {
                        orderByAtTopLevel = true
                    }
                }
            }
        }
        return orderByAtTopLevel && !orderByPermitted
    }

    /**
     * Traegt das Wort an [index] eine der Klauseln, die SQL Server ein
     * `ORDER BY` im View-Body erlauben — `SELECT TOP n`, `OFFSET n ROWS`,
     * `FETCH NEXT/FIRST …`, `FOR XML`/`FOR JSON`?
     *
     * Die Wortform allein genuegt dafuer nicht: alle vier sind in T-SQL auch
     * als Bezeichner zulaessig (`t.top`, `AS fetch`), und PostgreSQLs
     * `OFFSET n` **ohne** `ROWS` ist gerade *kein* T-SQL-Limiter — eine Sicht
     * mit `ORDER BY … OFFSET 10` waere ungueltiges T-SQL und muss als nicht
     * portabel gelten.
     */
    private fun permitsTopLevelOrderBy(tokens: List<ViewQueryToken>, index: Int): Boolean {
        val prev = tokens.take(index).lastOrNull { it.type != ViewQueryTokenType.WS }
        val usedAsIdentifier = prev != null && (
            prev.text == "." || prev.text == "[" ||
                (prev.type == ViewQueryTokenType.WORD && prev.text.equals("AS", ignoreCase = true))
            )
        if (usedAsIdentifier) return false
        val following = tokens.drop(index + 1).filter { it.type != ViewQueryTokenType.WS }
        val next = following.firstOrNull()
        return when (tokens[index].text.uppercase()) {
            // TOP n / TOP (n) — ein blosses `TOP` ist ein Spaltenname.
            "TOP" -> next != null &&
                (next.type == ViewQueryTokenType.NUMBER || next.type == ViewQueryTokenType.LPAREN)
            // OFFSET <expr> ROWS; das `ROWS` ist in T-SQL Pflicht.
            "OFFSET" -> following.take(OFFSET_ROWS_LOOKAHEAD)
                .any { it.type == ViewQueryTokenType.WORD && it.text.uppercase() in ROW_KEYWORDS }
            "FETCH" -> next.isWordIn(FETCH_KEYWORDS)
            "FOR" -> next.isWordIn(FOR_CLAUSE_KEYWORDS)
            else -> false
        }
    }

    private fun ViewQueryToken?.isWordIn(words: Set<String>): Boolean =
        this != null && type == ViewQueryTokenType.WORD && text.uppercase() in words

    /** Das naechste Wort nach [index], Whitespace uebersprungen. */
    private fun followingWord(tokens: List<ViewQueryToken>, index: Int): String? =
        tokens.drop(index + 1)
            .firstOrNull { it.type != ViewQueryTokenType.WS }
            ?.takeIf { it.type == ViewQueryTokenType.WORD }
            ?.text

    /**
     * `LIMIT` als Klausel (gefolgt von einer Zahl, `ALL` oder `OFFSET`) — nicht
     * als Spaltenname/Alias (`t.limit`, `AS limit`, `[limit]`), der in T-SQL
     * erlaubt ist.
     */
    private fun hasLimitClause(tokens: List<ViewQueryToken>): Boolean {
        for ((index, token) in tokens.withIndex()) {
            if (token.type != ViewQueryTokenType.WORD || !token.text.equals("LIMIT", ignoreCase = true)) continue
            val prev = tokens.take(index).lastOrNull { it.type != ViewQueryTokenType.WS }
            val next = tokens.drop(index + 1).firstOrNull { it.type != ViewQueryTokenType.WS }
            val prevBlocks = prev != null && (
                prev.text == "." || prev.text == "[" ||
                    (prev.type == ViewQueryTokenType.WORD && prev.text.equals("AS", ignoreCase = true))
                )
            val nextIsClauseArg = next != null && (
                next.type == ViewQueryTokenType.NUMBER ||
                    (next.type == ViewQueryTokenType.WORD && next.text.uppercase() in setOf("ALL", "OFFSET"))
                )
            if (!prevBlocks && nextIsClauseArg) return true
        }
        return false
    }

    fun transform(query: String, sourceDialect: String?): Pair<String, List<TransformationNote>> {
        val notes = mutableListOf<TransformationNote>()
        val tokens = ViewQueryTokenizer.tokenize(query)
        val transformed = applyRules(tokens)
        val result = ViewQueryTokenizer.render(transformed)

        if (sourceDialect != null && sourceDialect != targetDialect.name.lowercase()) {
            val unknownFunctions = detectUnknownFunctions(transformed)
            if (unknownFunctions.isNotEmpty()) {
                notes += TransformationNote(
                    type = NoteType.WARNING,
                    code = "W111",
                    objectName = "view_query",
                    message = "View query may contain dialect-specific functions: ${unknownFunctions.joinToString(", ")}",
                    hint = "Review and manually adjust if needed.",
                )
            }
        }

        return result to notes
    }

    private fun applyRules(tokens: List<ViewQueryToken>): List<ViewQueryToken> {
        var result = tokens.toMutableList()
        for (rule in getRules()) {
            result = rule.apply(result).toMutableList()
        }
        return result
    }

    private fun getRules(): List<ViewQueryRule> = when (targetDialect) {
        DatabaseDialect.MYSQL -> mysqlRules
        DatabaseDialect.SQLITE -> sqliteRules
        DatabaseDialect.POSTGRESQL -> postgresRules
        // Kein T-SQL-Umschreibregelwerk: Bodies passieren unveraendert; was
        // T-SQL nicht kennt (`::`, `||`, `LIMIT`, fremde Funktionen), meldet
        // assessPortability als nicht portabel (E053 beim Aufrufer).
        DatabaseDialect.MSSQL -> emptyList()
        // Noch kein Oracle-Umschreibregelwerk (Slice 1) -- analog MSSQL
        // passieren Bodies unveraendert, Nicht-Portabilitaet meldet E053.
        DatabaseDialect.ORACLE -> emptyList()
    }

    private val mysqlRules: List<ViewQueryRule> = listOf(
        FuncReplaceRule("DATE_TRUNC") { _, args ->
            if (args.size == 2) {
                val unit = args[0].firstOrNull { it.type == ViewQueryTokenType.STRING }?.text?.removeSurrounding("'")
                val column = args[1].dropWhile { it.type == ViewQueryTokenType.WS }
                when (unit) {
                    "month" -> ViewQueryRuleSupport.functionCall("DATE_FORMAT", column, "'%Y-%m-01'")
                    "year" -> ViewQueryRuleSupport.functionCall("DATE_FORMAT", column, "'%Y-01-01'")
                    "day" -> ViewQueryRuleSupport.wrapCall("DATE", column)
                    else -> ViewQueryRuleSupport.originalDateTrunc(args)
                }
            } else {
                ViewQueryRuleSupport.emptyFunctionCall("DATE_TRUNC")
            }
        },
        ExtractReplaceRule("YEAR") { expr -> ViewQueryRuleSupport.wrapCall("YEAR", expr) },
        ExtractReplaceRule("MONTH") { expr -> ViewQueryRuleSupport.wrapCall("MONTH", expr) },
        SubstringReplaceRule { expr, from, length ->
            ViewQueryRuleSupport.substringCall("SUBSTRING", expr, from, length)
        },
        FuncReplaceRule("LENGTH") { _, args ->
            ViewQueryRuleSupport.callWithArgs("CHAR_LENGTH", args)
        },
        WordReplaceRule("CURRENT_DATE", "CURDATE()"),
        WordReplaceRule("CURRENT_TIME", "CURTIME()"),
        WordReplaceRule("TRUE", "1"),
        WordReplaceRule("FALSE", "0"),
    )

    private val sqliteRules: List<ViewQueryRule> = listOf(
        FuncReplaceRule("NOW") { _, _ -> ViewQueryRuleSupport.literalCall("datetime", "'now'") },
        WordReplaceRule("CURRENT_TIMESTAMP", "datetime('now')"),
        WordReplaceRule("CURRENT_DATE", "date('now')"),
        WordReplaceRule("CURRENT_TIME", "time('now')"),
        FuncReplaceRule("DATE_TRUNC") { _, args ->
            if (args.size == 2) {
                val unit = args[0].firstOrNull { it.type == ViewQueryTokenType.STRING }?.text?.removeSurrounding("'")
                val column = args[1].dropWhile { it.type == ViewQueryTokenType.WS }
                when (unit) {
                    "month" -> ViewQueryRuleSupport.functionCall("strftime", column, "'%Y-%m-01'")
                    "year" -> ViewQueryRuleSupport.functionCall("strftime", column, "'%Y-01-01'")
                    "day" -> ViewQueryRuleSupport.wrapCall("date", column)
                    else -> ViewQueryRuleSupport.originalDateTrunc(args)
                }
            } else {
                ViewQueryRuleSupport.emptyFunctionCall("DATE_TRUNC")
            }
        },
        ExtractReplaceRule("YEAR") { expr ->
            ViewQueryRuleSupport.castStrftimeInt("'%Y'", expr)
        },
        ExtractReplaceRule("MONTH") { expr ->
            ViewQueryRuleSupport.castStrftimeInt("'%m'", expr)
        },
        FuncReplaceRule("CONCAT") { _, args ->
            if (args.size >= 2) {
                args.flatMapIndexed { index, arg ->
                    if (index > 0) {
                        listOf(
                            ViewQueryTokenSupport.ws(),
                            ViewQueryTokenSupport.other("||"),
                            ViewQueryTokenSupport.ws(),
                        ) + arg.dropWhile { it.type == ViewQueryTokenType.WS }
                    } else {
                        arg.dropWhile { it.type == ViewQueryTokenType.WS }
                    }
                }
            } else {
                ViewQueryRuleSupport.emptyFunctionCall("CONCAT")
            }
        },
        SubstringReplaceRule { expr, from, length ->
            ViewQueryRuleSupport.substringCall("SUBSTR", expr, from, length)
        },
        WordReplaceRule("TRUE", "1"),
        WordReplaceRule("FALSE", "0"),
    )

    private val postgresRules: List<ViewQueryRule> = listOf(
        FuncReplaceRule("NOW") { _, _ -> listOf(ViewQueryTokenSupport.word("CURRENT_TIMESTAMP")) },
    )

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
        "NOW", "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME",
        "DATE_TRUNC", "EXTRACT", "SUBSTRING", "LENGTH", "CHAR_LENGTH",
        "CONCAT", "YEAR", "MONTH", "DATE", "DATE_FORMAT", "CURDATE", "CURTIME",
        "STRFTIME", "SUBSTR", "DATETIME",
    )

    private val allKnown = transparentFunctions + sqlKeywords

    /**
     * Scalar/aggregate functions that are spelled and behave identically in
     * MySQL and PostgreSQL. Treated as known for those targets so a portable
     * cross-dialect view (e.g. `SELECT FLOOR(x)`) is not falsely flagged as
     * non-portable. NOT applied to SQLite, where several of these require the
     * optional math extension — keeping the SQLite verdict conservative.
     */
    private val mysqlPostgresPortableFunctions = setOf(
        "FLOOR", "CEIL", "CEILING", "MOD", "POWER", "SQRT", "SIGN", "EXP", "LN", "LOG",
        "GREATEST", "LEAST", "LTRIM", "RTRIM",
    )

    /**
     * Funktionsaufrufe, die T-SQL kennt. Die generische Grundmenge enthält
     * PG-/MySQL-/SQLite-Spellings (`NOW()`, `DATE_TRUNC`, `STRFTIME`, …), für
     * die es keine MSSQL-Umschreibregel gibt — sie müssen hier als unbekannt
     * gelten, sonst landet ein solcher Body verbatim in `CREATE OR ALTER VIEW`.
     */
    private val tsqlOnlyFunctions = setOf(
        "LEN", "GETDATE", "GETUTCDATE", "SYSDATETIME", "SYSDATETIMEOFFSET", "SYSUTCDATETIME",
        "DATEADD", "DATEDIFF", "DATEPART", "DATENAME", "DAY", "EOMONTH", "DATEFROMPARTS",
        "ISNULL", "IIF", "CONVERT", "TRY_CAST", "TRY_CONVERT", "NEWID", "FORMAT", "STR",
        "CHARINDEX", "PATINDEX", "STUFF", "REPLICATE", "REVERSE", "SPACE", "CONCAT_WS", "STRING_AGG",
        "LEFT", "RIGHT", "LTRIM", "RTRIM", "FLOOR", "CEILING", "POWER", "SQRT", "SIGN", "EXP",
        "LOG", "LOG10", "COUNT_BIG", "ISDATE", "ISNUMERIC", "ROW_NUMBER", "RANK", "DENSE_RANK",
        "NTILE", "LAG", "LEAD", "OVER", "PARTITION",
    )
    private val notTsqlFunctions = setOf(
        "NOW", "DATE_TRUNC", "EXTRACT", "LENGTH", "CHAR_LENGTH", "DATE_FORMAT", "CURDATE", "CURTIME",
        "STRFTIME", "SUBSTR", "DATETIME", "DATE", "TIME", "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME",
    )

    private fun knownFunctions(): Set<String> = when (targetDialect) {
        DatabaseDialect.MYSQL, DatabaseDialect.POSTGRESQL -> allKnown + mysqlPostgresPortableFunctions
        // Konservativ: nur die Grundmenge, bis ein SQLite-Verdict die portablen
        // Funktionen belegt.
        DatabaseDialect.SQLITE -> allKnown
        DatabaseDialect.MSSQL -> (allKnown - notTsqlFunctions) + tsqlOnlyFunctions
        // Konservativ wie SQLite: nur die Grundmenge, bis ein Oracle-Verdict
        // die portablen/Oracle-eigenen Funktionen belegt (analog tsqlOnly-
        // /notTsqlFunctions fuer MSSQL).
        DatabaseDialect.ORACLE -> allKnown
    }

    private fun detectUnknownFunctions(tokens: List<ViewQueryToken>): List<String> {
        val known = knownFunctions()
        val unknown = mutableListOf<String>()
        for ((index, token) in tokens.withIndex()) {
            if (token.type != ViewQueryTokenType.WORD) continue
            val next = tokens.drop(index + 1).firstOrNull { it.type != ViewQueryTokenType.WS }
            if (next?.type == ViewQueryTokenType.LPAREN && token.text.uppercase() !in known) {
                unknown += token.text.uppercase()
            }
        }
        return unknown.distinct()
    }

    private companion object {
        /** `OFFSET <expr> ROWS` — der Ausdruck ist praktisch immer ein Token, drei sind Puffer. */
        const val OFFSET_ROWS_LOOKAHEAD = 4
        val ROW_KEYWORDS = setOf("ROW", "ROWS")
        val FETCH_KEYWORDS = setOf("NEXT", "FIRST")
        val FOR_CLAUSE_KEYWORDS = setOf("XML", "JSON")
    }
}
