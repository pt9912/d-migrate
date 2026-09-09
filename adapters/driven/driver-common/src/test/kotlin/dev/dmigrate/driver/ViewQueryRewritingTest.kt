package dev.dmigrate.driver

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Das Umschreib-Vokabular fuer sich — ohne Dialekt.
 *
 * Es ist die eigentliche Rechtfertigung dafuer, dass diese Bausteine in einem
 * geteilten Modul liegen: sie haben eine eigene Identitaet („SQL-Text ueber
 * Token umschreiben"), unabhaengig davon, welcher Server am Ende gemeint ist.
 * Bis zur Aufteilung wurden sie nur nebenbei durch die Dialekt-Tests
 * mitgeprueft; jetzt stehen ihre Zusicherungen dort, wo der Code steht.
 *
 * Der Token-Ansatz existiert wegen der Faelle unten: ein reiner Textersatz
 * traefe auch in Zeichenketten und in Teilbezeichnern.
 */
class ViewQueryRewritingTest : FunSpec({

    fun rewrite(sql: String, vararg rules: ViewQueryRule): String {
        var tokens = ViewQueryTokenizer.tokenize(sql)
        for (rule in rules) tokens = rule.apply(tokens)
        return ViewQueryTokenizer.render(tokens)
    }

    // ── WordReplaceRule ──────────────────────────────────────────

    test("ein Wort wird ersetzt, ein gleichnamiger Funktionsaufruf nicht") {
        // `CURRENT_DATE` als Wert ist etwas anderes als `CURRENT_DATE(...)`
        // als Aufruf; die Regel ist fuer das Erste gedacht.
        val rule = WordReplaceRule("CURRENT_DATE", "CURDATE()")
        rewrite("SELECT CURRENT_DATE FROM t", rule) shouldBe "SELECT CURDATE() FROM t"
        rewrite("SELECT CURRENT_DATE(x) FROM t", rule) shouldBe "SELECT CURRENT_DATE(x) FROM t"
    }

    test("die Ersetzung achtet nicht auf Gross- und Kleinschreibung, aber auf Wortgrenzen") {
        val rule = WordReplaceRule("TRUE", "1")
        rewrite("SELECT true FROM t", rule) shouldBe "SELECT 1 FROM t"
        // `truename` ist ein anderer Bezeichner — ein Textersatz haette ihn zerlegt.
        rewrite("SELECT truename FROM t", rule) shouldBe "SELECT truename FROM t"
    }

    test("in einer Zeichenkette wird nichts ersetzt") {
        // Genau dafuer gibt es den Tokenizer statt eines regulaeren Ausdrucks.
        val rule = WordReplaceRule("TRUE", "1")
        rewrite("SELECT 'TRUE' FROM t", rule) shouldBe "SELECT 'TRUE' FROM t"
    }

    // ── FuncReplaceRule ──────────────────────────────────────────

    test("ein Funktionsaufruf wird samt Argumenten ersetzt") {
        val rule = FuncReplaceRule("LENGTH") { _, args ->
            listOf(ViewQueryTokenSupport.word("CHAR_LENGTH"), ViewQueryTokenSupport.lparen()) +
                args.first() + listOf(ViewQueryTokenSupport.rparen())
        }
        rewrite("SELECT LENGTH(name) FROM t", rule) shouldBe "SELECT CHAR_LENGTH(name) FROM t"
    }

    test("geschachtelte Klammern zaehlen mit, das Argument bleibt vollstaendig") {
        val rule = FuncReplaceRule("OUTER") { _, args -> args.first() }
        rewrite("SELECT OUTER(COALESCE(a, (b + c))) FROM t", rule) shouldBe
            "SELECT COALESCE(a, (b + c)) FROM t"
    }

    test("mehrere Argumente kommen einzeln und in Reihenfolge an") {
        // Die Argumente tragen ihren Leerraum mit — wer sie neu zusammensetzt,
        // setzt die Trenner selbst. Genau das macht der Aufbau sichtbar.
        val rule = FuncReplaceRule("PAIR") { _, args ->
            args[1].filter { it.type != ViewQueryTokenType.WS } +
                listOf(ViewQueryTokenSupport.ws(), ViewQueryTokenSupport.word("AND"), ViewQueryTokenSupport.ws()) +
                args[0].filter { it.type != ViewQueryTokenType.WS }
        }
        rewrite("SELECT PAIR(a, b) FROM t", rule) shouldBe "SELECT b AND a FROM t"
    }

    test("ein unvollstaendiger Aufruf bleibt unangetastet") {
        // Ohne schliessende Klammer gibt es keine Argumentliste — die Regel
        // darf den Text dann nicht halb umschreiben.
        val rule = FuncReplaceRule("LENGTH") { _, _ -> listOf(ViewQueryTokenSupport.word("X")) }
        rewrite("SELECT LENGTH(name FROM t", rule) shouldBe "SELECT LENGTH(name FROM t"
    }

    test("ein gleichnamiges Wort ohne Klammer ist kein Aufruf") {
        val rule = FuncReplaceRule("LENGTH") { _, _ -> listOf(ViewQueryTokenSupport.word("X")) }
        rewrite("SELECT length FROM t", rule) shouldBe "SELECT length FROM t"
    }

    // ── ExtractReplaceRule ───────────────────────────────────────

    test("EXTRACT(<unit> FROM expr) wird als Ganzes erkannt und ersetzt") {
        // `EXTRACT` ist kein gewoehnlicher Funktionsaufruf: die Einheit steht
        // als Wort vor einem `FROM`, nicht als Argument mit Komma.
        val rule = ExtractReplaceRule("YEAR") { expr ->
            listOf(ViewQueryTokenSupport.word("YEAR"), ViewQueryTokenSupport.lparen()) +
                expr + listOf(ViewQueryTokenSupport.rparen())
        }
        rewrite("SELECT EXTRACT(YEAR FROM created_at) FROM t", rule) shouldBe
            "SELECT YEAR(created_at) FROM t"
    }

    test("eine andere Einheit laesst die Regel unberuehrt") {
        val rule = ExtractReplaceRule("YEAR") { _ -> listOf(ViewQueryTokenSupport.word("X")) }
        rewrite("SELECT EXTRACT(MONTH FROM created_at) FROM t", rule) shouldBe
            "SELECT EXTRACT(MONTH FROM created_at) FROM t"
    }

    test("der Ausdruck hinter FROM darf selbst Klammern tragen") {
        val rule = ExtractReplaceRule("YEAR") { expr -> expr }
        rewrite("SELECT EXTRACT(YEAR FROM COALESCE(a, b)) FROM t", rule) shouldBe
            "SELECT COALESCE(a, b) FROM t"
    }

    // ── SubstringReplaceRule ─────────────────────────────────────

    test("SUBSTRING(expr FROM n FOR m) wird in seine drei Teile zerlegt") {
        val rule = SubstringReplaceRule { expr, from, length ->
            listOf(ViewQueryTokenSupport.word("SUBSTR"), ViewQueryTokenSupport.lparen()) + expr +
                listOf(
                    ViewQueryTokenSupport.comma(), ViewQueryTokenSupport.word(from),
                    ViewQueryTokenSupport.comma(), ViewQueryTokenSupport.word(length),
                    ViewQueryTokenSupport.rparen(),
                )
        }
        rewrite("SELECT SUBSTRING(name FROM 2 FOR 5) FROM t", rule) shouldBe
            "SELECT SUBSTR(name,2,5) FROM t"
    }

    test("ein gewoehnliches SUBSTRING mit Kommas bleibt unberuehrt") {
        // Ohne `FROM`/`FOR` ist es die andere Schreibweise — die Regel gilt
        // ihr nicht.
        val rule = SubstringReplaceRule { _, _, _ -> listOf(ViewQueryTokenSupport.word("X")) }
        rewrite("SELECT SUBSTRING(name, 2, 5) FROM t", rule) shouldBe "SELECT SUBSTRING(name, 2, 5) FROM t"
    }

    // ── Argument-Zerlegung ───────────────────────────────────────

    test("die Argumentliste endet an der schliessenden Klammer, nicht an der ersten") {
        val tokens = ViewQueryTokenizer.tokenize("F(a, g(b, c), d)")
        val lparen = tokens.indexOfFirst { it.type == ViewQueryTokenType.LPAREN }
        val (args, end) = ViewQueryRuleSupport.extractArgs(tokens, lparen)
        args.size shouldBe 3
        ViewQueryTokenizer.render(args[1]).trim() shouldBe "g(b, c)"
        end shouldBe tokens.size - 1
    }

    test("eine offene Klammer liefert kein Ende — und die Regel weiss es") {
        val tokens = ViewQueryTokenizer.tokenize("F(a, b")
        val lparen = tokens.indexOfFirst { it.type == ViewQueryTokenType.LPAREN }
        ViewQueryRuleSupport.extractArgs(tokens, lparen).second shouldBe -1
        ViewQueryRuleSupport.extractInnerTokens(tokens, lparen) shouldBe null
    }

    // ── Bau-Helfer ───────────────────────────────────────────────

    test("die Bau-Helfer erzeugen genau den Aufruf, den ihr Name verspricht") {
        // Sie liefen bisher nur nebenbei ueber die Dialektregeln mit. Als
        // eigene Bausteine gepruefte Ausgabe ist billiger zu lesen als eine
        // ganze Umschreibung, die zufaellig durch sie hindurchlaeuft.
        fun render(tokens: List<ViewQueryToken>) = ViewQueryTokenizer.render(tokens)
        val expr = ViewQueryTokenizer.tokenize("created_at")

        render(ViewQueryRuleSupport.emptyFunctionCall("CURDATE")) shouldBe "CURDATE()"
        render(ViewQueryRuleSupport.literalCall("DATETIME", "'now'")) shouldBe "DATETIME('now')"
        render(ViewQueryRuleSupport.wrapCall("YEAR", expr)) shouldBe "YEAR(created_at)"
        render(ViewQueryRuleSupport.functionCall("DATE_FORMAT", expr, "'%Y'")) shouldBe
            "DATE_FORMAT(created_at, '%Y')"
        render(ViewQueryRuleSupport.substringCall("SUBSTR", expr, "2", "5")) shouldBe
            "SUBSTR(created_at, 2, 5)"
    }

    test("castStrftimeInt baut den vollstaendigen CAST — nicht nur das strftime") {
        // Ohne den CAST kaeme aus `strftime` Text; ein Vergleich mit einer
        // Zahl ginge dann still schief.
        val expr = ViewQueryTokenizer.tokenize("created_at")
        ViewQueryTokenizer.render(ViewQueryRuleSupport.castStrftimeInt("'%m'", expr)) shouldBe
            "CAST(strftime('%m', created_at) AS INTEGER)"
    }

    test("callWithArgs setzt die Trenner zwischen die Argumente, nicht davor") {
        val args = listOf(ViewQueryTokenizer.tokenize("a"), ViewQueryTokenizer.tokenize("b"))
        ViewQueryTokenizer.render(ViewQueryRuleSupport.callWithArgs("F", args)) shouldBe "F(a, b)"
        // Der Rueckfall auf den urspruenglichen Aufruf ist derselbe Bau.
        ViewQueryTokenizer.render(ViewQueryRuleSupport.originalDateTrunc(args)) shouldBe "DATE_TRUNC(a, b)"
    }

    // ── Tokenizer ────────────────────────────────────────────────

    test("was hineingeht, kommt unveraendert wieder heraus, wenn keine Regel greift") {
        val sql = "SELECT a.b, 'text with ''quote''', 42 FROM t /* comment */ WHERE x = 1"
        rewrite(sql) shouldBe sql
    }

    // ── Klausel-Erkennung ────────────────────────────────────────

    test("ein LIMIT mit Argument zaehlt, ein Bezeichner namens limit nicht") {
        fun hasLimit(sql: String) = ViewQueryClauseDetection.hasLimitClause(ViewQueryTokenizer.tokenize(sql))
        hasLimit("SELECT a FROM t LIMIT 10") shouldBe true
        hasLimit("SELECT a FROM t LIMIT ALL") shouldBe true
        hasLimit("SELECT quota AS limit FROM t") shouldBe false
        hasLimit("SELECT p.limit FROM plans p") shouldBe false
    }

    test("jeder T-SQL-Limitierer hebt das Urteil auf, ein blosses Wort nicht") {
        // `TOP`, `OFFSET … ROWS`, `FETCH NEXT` und `FOR XML` sind die Formen,
        // die SQL Server neben einem `ORDER BY` im View-Body verlangt. Die
        // Wortform allein genuegt keiner davon — alle vier sind auch
        // zulaessige Bezeichner.
        fun bare(sql: String) = ViewQueryClauseDetection.hasBareTopLevelOrderBy(ViewQueryTokenizer.tokenize(sql))
        bare("SELECT a FROM t ORDER BY a FETCH NEXT 5 ROWS ONLY") shouldBe false
        bare("SELECT a FROM t ORDER BY a FOR XML PATH") shouldBe false
        bare("SELECT TOP 10 a FROM t ORDER BY a") shouldBe false
        // `OFFSET n` ohne `ROWS` ist PostgreSQL, nicht T-SQL.
        bare("SELECT a FROM t ORDER BY a OFFSET 10") shouldBe true
    }

    test("ein ORDER BY auf oberster Ebene zaehlt, eines in Klammern nicht") {
        fun bare(sql: String) = ViewQueryClauseDetection.hasBareTopLevelOrderBy(ViewQueryTokenizer.tokenize(sql))
        bare("SELECT a FROM t ORDER BY a") shouldBe true
        // In einer Fensterfunktion oder Unterabfrage steht es in Klammern.
        bare("SELECT ROW_NUMBER() OVER (ORDER BY a) FROM t") shouldBe false
        bare("SELECT * FROM (SELECT a FROM t ORDER BY a) s") shouldBe false
        // Mit einem echten Limitierer ist es fuer T-SQL zulaessig.
        bare("SELECT a FROM t ORDER BY a OFFSET 10 ROWS") shouldBe false
    }
})
