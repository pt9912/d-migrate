package dev.dmigrate.driver

import dev.dmigrate.core.model.IndexDefinition

/**
 * Ob ein **skalarer** roher SQL-Ausdruck auf dem Zieldialekt ueberhaupt
 * parsebar ist.
 *
 * Das neutrale Modell traegt rohen SQL-Text an drei Stellen, die keine
 * Anweisung sind, sondern ein Ausdruck: `ConstraintDefinition.expression`
 * (CHECK/EXCLUDE), `IndexDefinition.where` und `IndexColumn.expression`. Fuer
 * Sichten-Ruempfe beurteilt [ViewQueryTransformer.assessPortability] die
 * Portabilitaet seit jeher; fuer diese drei tat es niemand — sie gingen
 * unveraendert in die DDL. Gemessen: ein zurueckgelesener PostgreSQL-CHECK
 * `((email ~~ '%@%'::text))` landete woertlich in T-SQL, MySQL, SQLite und
 * Oracle, wo weder `~~` noch `::` existiert.
 *
 * **Beurteilt wird das ZIEL, nicht die Herkunft.** `ConstraintDefinition`
 * traegt kein `sourceDialect` — der Ausdruck ist der einzige rohe Text im
 * Modell ohne jedes Herkunftssignal. Das ist hier kein Mangel: die Marker
 * unten sind Eigenschaften der Ziel-Grammatik. `::` ist in T-SQL ein
 * Syntaxfehler, gleichgueltig, wer es geschrieben hat.
 *
 * **Bewusst nur harte Fehler.** Was ohne Herkunft nicht sicher zu entscheiden
 * ist, bleibt draussen:
 *
 * - **T-SQL-Klammern** (`[dbo].[t]`) sind ausserhalb von SQL Server ungueltig,
 *   aber `[` steht ebenso in JSON-Pfaden und Array-Ausdruecken. Ohne Herkunft
 *   waere die Unterscheidung geraten.
 * - **`||` gegen MySQL** ist dort gueltig — als logisches ODER. Der Ausdruck
 *   kippt still von Verkettung auf ODER, aber ob der Autor nicht genau das
 *   meinte, sagt nur die Herkunft. Gegen **SQL Server** ist `||` dagegen
 *   ueberhaupt kein Operator, also ein harter Fehler und hier gemeldet.
 *
 * Die Prueflaeufe arbeiten auf dem Token-Strom, nicht auf der Zeichenkette:
 * ein `::` **in einem Zeichenketten-Literal** ist kein Cast.
 */
object RawSqlExpressionPortability {

    data class Verdict(val portable: Boolean, val reason: String?)

    private val PORTABLE = Verdict(portable = true, reason = null)

    fun assess(expression: String?, target: DatabaseDialect): Verdict {
        if (expression.isNullOrBlank()) return PORTABLE
        val tokens = ViewQueryTokenizer.tokenize(expression)
        // Zeichenketten-Literale ausblenden: dort ist `::` Text, kein Cast.
        val code = tokens.joinToString("") { if (it.type == ViewQueryTokenType.STRING) " " else it.text }
        val markers = mutableListOf<String>()

        if (target != DatabaseDialect.POSTGRESQL) {
            if (code.contains("::")) markers += "PostgreSQL-style cast (::)"
            // `~~`/`~~*`/`!~~`/`!~~*` sind PostgreSQLs interne Operatoren fuer
            // LIKE/ILIKE; der Reverse liefert sie statt des Schluesselworts.
            if (code.contains("~~")) markers += "PostgreSQL-internal LIKE operator (~~)"
        }
        if (target != DatabaseDialect.MYSQL &&
            tokens.any { it.type == ViewQueryTokenType.WORD && it.text.startsWith("`") }
        ) {
            markers += "MySQL-style backtick quoting"
        }
        // T-SQL kennt `||` nicht als Operator -- weder Verkettung noch ODER.
        if (target == DatabaseDialect.MSSQL && code.contains("||")) {
            markers += "PostgreSQL/SQLite-style concatenation (||)"
        }

        return if (markers.isEmpty()) PORTABLE else Verdict(false, markers.joinToString("; "))
    }

    /**
     * Der ganze rohe Text eines Index: sein Praedikat und jeder
     * Ausdrucks-Schluessel. Beides landet in derselben `CREATE INDEX`-
     * Anweisung, also entscheidet auch beides gemeinsam ueber sie.
     */
    fun assessIndex(index: IndexDefinition, target: DatabaseDialect): Verdict {
        val parts = listOf(index.where) + index.columns.map { it.expression }
        for (part in parts) {
            val verdict = assess(part, target)
            if (!verdict.portable) return verdict
        }
        return PORTABLE
    }

    /**
     * Die Absage fuer einen Index, dessen roher Text auf dem Ziel nicht gilt —
     * eine leere Anweisung mit Begruendung, `null` wenn er gilt.
     *
     * Als gemeinsame Stelle statt je Dialekt: die Absage ist ueberall
     * dieselbe, und vier Kopien waeren vier Gelegenheiten, dass sie
     * auseinanderlaufen.
     */
    fun indexRefusal(index: IndexDefinition, indexName: String, target: DatabaseDialect): DdlStatement? {
        val verdict = assessIndex(index, target)
        if (verdict.portable) return null
        return DdlStatement(
            "",
            listOf(notPortableNote("index", indexName, "raw SQL text", verdict.reason, target)),
        )
    }

    /**
     * Der **Berechnungsausdruck** einer Spalte — `null`, wenn er auf dem Ziel
     * gilt, sonst die Absage.
     *
     * Dasselbe Wesen wie ein CHECK, und derselbe Grund: der Reverse uebersetzt
     * rohen Text nicht, also darf das Rendern gegen ein anderes Ziel ihn nicht
     * weiterreichen. Gemeldet von einem Konsumenten an genau diesem Fall: ein
     * PostgreSQL-Reverse liefert `((quantity)::numeric * unit_price)`, und
     * `::` gibt es in T-SQL, MySQL, SQLite und Oracle nicht.
     *
     * **Die Spalte bleibt, ihre Berechnung geht.** Sie ganz wegzulassen liesse
     * eine unvollstaendige Tabelle entstehen; sie berechnet zu rendern ergaebe
     * ungueltiges SQL. Uebrig bleibt die gewoehnliche Spalte — und die Meldung
     * ist `ACTION_REQUIRED`, damit niemand das fuer den Normalfall haelt.
     */
    fun computedRefusal(columnName: String, expression: String?, target: DatabaseDialect): TransformationNote? {
        val verdict = assess(expression, target)
        if (verdict.portable) return null
        return notPortableNote("column", columnName, "computed expression", verdict.reason, target)
    }

    /**
     * Die Meldung fuer einen Ausdruck, der auf dem Ziel nicht parsebar ist —
     * eine Stelle fuer alle fuenf Dialekte, damit aus einer Fassung nicht
     * fuenf leicht verschiedene werden.
     *
     * `E053` ist derselbe Code, mit dem ein nicht portabler Sichten-Rumpf
     * ausgewiesen wird: dieselbe Aussage („der Text gilt hier nicht, und
     * uebersetzt wird er nicht"), nur an einem anderen Feld.
     */
    fun notPortableNote(
        objectType: String,
        objectName: String,
        field: String,
        reason: String?,
        target: DatabaseDialect,
    ): TransformationNote = ManualActionRequired(
        code = "E053",
        objectType = objectType,
        objectName = objectName,
        reason = "The $field of $objectType '$objectName' is not valid for " +
            "${target.name.lowercase()} (${reason ?: "unsupported syntax"}); d-migrate does not translate raw " +
            "SQL expressions between dialects, so it was not rendered.",
        hint = "Rewrite the expression with ${target.name.lowercase()}-compatible syntax and re-run.",
    ).toNote()
}
