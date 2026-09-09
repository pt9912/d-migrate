package dev.dmigrate.driver

/**
 * Klausel-Erkennung auf dem Token-Strom — dialektneutral, weil das Erkennen
 * es ist: ob ein Rumpf ein `LIMIT` traegt oder ein `ORDER BY` auf oberster
 * Ebene, ist eine Frage an den Text. **Was daraus folgt**, ist dagegen
 * dialektabhaengig und steht bei den jeweiligen Regeln.
 *
 * Aus [ViewQueryTransformer] herausgeloest, damit mehrere Dialekte dieselbe
 * Erkennung benutzen statt sie zu wiederholen: `LIMIT` betrifft SQL Server
 * wie Oracle, das nackte `ORDER BY` heute nur SQL Server.
 */
object ViewQueryClauseDetection {

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
    fun hasBareTopLevelOrderBy(tokens: List<ViewQueryToken>): Boolean {
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
    fun hasLimitClause(tokens: List<ViewQueryToken>): Boolean {
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

    /** `OFFSET <expr> ROWS` — der Ausdruck ist praktisch immer ein Token, drei sind Puffer. */
    private const val OFFSET_ROWS_LOOKAHEAD = 4
    private val ROW_KEYWORDS = setOf("ROW", "ROWS")
    private val FETCH_KEYWORDS = setOf("NEXT", "FIRST")
    private val FOR_CLAUSE_KEYWORDS = setOf("XML", "JSON")
}
