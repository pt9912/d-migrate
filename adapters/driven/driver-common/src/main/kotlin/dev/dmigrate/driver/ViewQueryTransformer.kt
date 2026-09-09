package dev.dmigrate.driver

/**
 * Prueft einen View-Rumpf auf Portabilitaet und schreibt ihn um, soweit der
 * Zieldialekt dafuer Regeln mitbringt.
 *
 * **Die Huelle kennt keinen Dialekt mehr.** Was ein einzelnes Ziel nicht
 * versteht, steht in seinem [ViewPortabilityRules]-Objekt im jeweiligen
 * Treibermodul; hier bleibt, was fuer alle gilt: tokenisieren, die Regeln
 * fragen, unbekannte Funktionen suchen, umschreiben.
 *
 * Zwei Marker bleiben trotzdem hier, und zwar mit Grund: **fremde
 * Quotierung** ist eine Aussage ueber die **Quelle**, nicht ueber das Ziel.
 * MySQLs Backticks und T-SQLs Klammern sind fuer jeden anderen Dialekt
 * unlesbar — in fuenf Regelobjekten stuende dieselbe Zeile viermal.
 */
class ViewQueryTransformer(private val rules: ViewPortabilityRules) {

    /** Ergebnis von [assessPortability]: ob ein View-Rumpf woertlich uebernommen werden darf. */
    data class ViewPortability(val portable: Boolean, val reason: String?)

    /**
     * Urteil darueber, ob der Rumpf fuer den Zieldialekt woertlich taugt.
     * d-migrate uebersetzt View-Rumpfe nicht zwischen Dialekten (es gibt
     * keinen SQL-Transpiler); ein Rumpf mit fremder Bezeichner-Quotierung
     * oder — bei fremder Herkunft — dialektspezifischen Funktionen gilt
     * deshalb als nicht portabel. Aufrufer ueberspringen solche Sichten mit
     * einer E053-Meldung, statt DDL zu erzeugen, das das Ziel ablehnt.
     */
    fun assessPortability(query: String, sourceDialect: String?): ViewPortability {
        val context = contextFor(query, sourceDialect)
        val markers = foreignQuotingMarkers(context).toMutableList()
        markers += rules.markers(context)
        if (context.crossDialect) {
            val unknown = detectUnknownFunctions(applyRules(context.tokens))
            if (unknown.isNotEmpty()) {
                markers += "dialect-specific function(s): ${unknown.joinToString(", ")}"
            }
        }
        return ViewPortability(portable = markers.isEmpty(), reason = markers.joinToString("; ").ifEmpty { null })
    }

    fun transform(query: String, sourceDialect: String?): Pair<String, List<TransformationNote>> {
        val notes = mutableListOf<TransformationNote>()
        val tokens = ViewQueryTokenizer.tokenize(query)
        val transformed = applyRules(tokens)
        val result = ViewQueryTokenizer.render(transformed)

        if (sourceDialect != null && sourceDialect != rules.dialect.name.lowercase()) {
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

    private fun contextFor(query: String, sourceDialect: String?): ViewPortabilityContext {
        // Ueber DatabaseDialect normalisiert, damit Aliasnamen ("postgres"/"pg"/
        // "maria"/…) nicht als fremder Dialekt gelten; unlesbare Werte bleiben
        // konservativ fremd.
        val crossDialect = sourceDialect != null &&
            runCatching { DatabaseDialect.fromString(sourceDialect) }.getOrNull() != rules.dialect
        return ViewPortabilityContext(
            tokens = ViewQueryTokenizer.tokenize(query),
            sourceDialect = sourceDialect,
            crossDialect = crossDialect,
        )
    }

    /**
     * Quotierung, die nur ein Dialekt versteht — eine Aussage ueber die
     * Herkunft des Rumpfes, nicht ueber das Ziel.
     */
    private fun foreignQuotingMarkers(context: ViewPortabilityContext): List<String> {
        val markers = mutableListOf<String>()
        // Backticks sind MySQL-Quotierung und in PG/SQLite/T-SQL/Oracle ein
        // harter Syntaxfehler. Am Token-Strom geprueft, damit ein Backtick in
        // einer Zeichenkette unbeachtet bleibt.
        if (rules.dialect != DatabaseDialect.MYSQL &&
            context.tokens.any { it.type == ViewQueryTokenType.WORD && it.text.startsWith("`") }
        ) {
            markers += "MySQL-style backtick quoting"
        }
        // Umgekehrt ist T-SQL-Klammer-Quoting (`[dbo].[users]`) nirgends sonst
        // gueltig. Nur bei mssql-staemmiger Herkunft geprueft: `[` hat in
        // anderen Dialekten eigene Bedeutungen (PostgreSQL indiziert damit
        // Arrays).
        if (rules.dialect != DatabaseDialect.MSSQL &&
            context.sourceIs(DatabaseDialect.MSSQL) &&
            context.codeOnly.contains("[")
        ) {
            markers += "T-SQL bracket quoting"
        }
        return markers
    }

    private fun applyRules(tokens: List<ViewQueryToken>): List<ViewQueryToken> {
        var result = tokens.toMutableList()
        for (rule in rules.rules()) {
            result = rule.apply(result).toMutableList()
        }
        return result
    }

    private fun detectUnknownFunctions(tokens: List<ViewQueryToken>): List<String> {
        val known = rules.knownFunctions()
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
}
