package dev.dmigrate.driver

import dev.dmigrate.core.model.IndexColumn

/**
 * Ein Indexschluessel als DDL — eine Spalte (gequotet) oder ein Ausdruck
 * (wortgleich, **in Klammern**).
 *
 * Die Klammern sind nicht Zierde, sondern gemessen noetig:
 *
 * - **PostgreSQL** verlangt sie fuer jeden Ausdruck, der kein blosser
 *   Funktionsaufruf ist: `(nm || 'x')` ist ein Syntaxfehler, `((nm || 'x'))`
 *   laeuft.
 * - **MySQL 8** verlangt sie immer; einfach geklammert liest es den Ausdruck
 *   als Spaltenliste und lehnt ab.
 * - **Oracle** nimmt beide Formen an und gibt die Klammern beim Zuruecklesen
 *   ohnehin nicht zurueck.
 *
 * Eine gemeinsame Quelle, weil jeder Dialekt **zwei** Index-Renderer hat —
 * einen fuer `schema generate`, einen fuer den Diff-Pfad. Getrennt gepflegt
 * hat der zweite den Ausdruck als Bezeichner gequotet, was einen Index auf
 * eine Spalte gelegt haette, die es nicht gibt.
 */
fun IndexColumn.renderKey(quoteIdentifier: (String) -> String): String =
    expression?.let { closeKeyParenthesis(it) } ?: quoteIdentifier(name)

/**
 * `(<ausdruck>)` -- endet der Ausdruck aber in einem `--`-Zeilenkommentar, steht
 * die schliessende Klammer auf einer **neuen Zeile**: sonst gehoert sie zum
 * Kommentar, und die Anweisung ist ungueltig (und das bei Exit 0). Der Text
 * des Ausdrucks bleibt wortgleich; ein Ausdruck ohne Zeilenkommentar rendert
 * unveraendert `(<ausdruck>)`. Die Erkennung ist grosszuegig: ein Fehlalarm
 * (`--` in einem Bezeichner) setzt nur einen Zeilenumbruch mehr, ein Fehlschlag
 * liefe auf ungueltiges DDL hinaus.
 */
private fun closeKeyParenthesis(expression: String): String =
    if (endsInLineComment(expression)) "($expression\n)" else "($expression)"

/** Ob ein `--`-Kommentar (ausserhalb von Zeichenketten und Blockkommentaren) bis zum Textende reicht. */
internal fun endsInLineComment(expression: String): Boolean {
    var i = 0
    while (i < expression.length) {
        val c = expression[i]
        i = when {
            expression.startsWith("--", i) ->
                expression.indexOf('\n', i).also { if (it < 0) return true }
            expression.startsWith("/*", i) ->
                expression.indexOf("*/", i + 2).let { if (it < 0) expression.length else it + 2 }
            c == '\'' || c == '"' || c == '`' -> skipQuoted(expression, i, c)
            else -> i + 1
        }
    }
    return false
}

/** Ein Begrenzer wird durch Verdoppeln eingebettet. */
private fun skipQuoted(text: String, start: Int, delimiter: Char): Int {
    var i = start + 1
    while (i < text.length) {
        if (text[i] == delimiter) {
            if (i + 1 < text.length && text[i + 1] == delimiter) i += 2 else return i + 1
        } else {
            i++
        }
    }
    return text.length
}
