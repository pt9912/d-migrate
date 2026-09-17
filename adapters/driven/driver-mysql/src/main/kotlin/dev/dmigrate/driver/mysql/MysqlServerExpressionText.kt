package dev.dmigrate.driver.mysql

import dev.dmigrate.driver.NeutralExpressionIdentifier

/**
 * Bringt einen Ausdruck, wie `information_schema` ihn fuehrt
 * (`CHECK_CONSTRAINTS.CHECK_CLAUSE`, `COLUMNS.GENERATION_EXPRESSION`), in die
 * neutrale Schreibweise.
 *
 * Der Server druckt den Ausdruck aus seinem Parsebaum, und zwar in
 * **MySQL-Oberflaechensyntax**; im neutralen Modell steht derselbe Ausdruck
 * dialektfrei. Drei Anhaenge fallen dabei weg — und nur sie:
 *
 * - der **Zeichensatz-Introducer** eines String-Literals (`_latin1'…'`,
 *   `_utf8mb4'…'`). Er ist der Zeichensatz der Sitzung, die den CHECK anlegte;
 *   ohne ihn zu entfernen liest die Validierung `_latin1` als Spalte und weist
 *   jedes zurueckgelesene MySQL-Schema mit `E012` (CHECK) bzw. `E136`
 *   (Berechnungsausdruck) ab;
 * - die **Backslash-Escapes** in einem Literal: MySQL schreibt `'it\'s'`, der
 *   SQL-Standard `'it''s'`;
 * - das **Backtick-Quoting** eines Bezeichners. Es macht jeden Ausdruck auf
 *   jedem anderen Ziel unportabel (`E053`), und das neutrale Modell hat mit
 *   [NeutralExpressionIdentifier] eine eigene Schreibweise dafuer.
 *
 * Der Praezedenzfall ist der SQL-Server-Reader
 * (`MssqlTypeMapping.normalizeExpression`): dieselbe Aufgabe, dieselbe
 * Grenze — der Ausdruck wird **nicht** uebersetzt, nur seine
 * Dialekt-Anhaenge fallen weg. Die Regel steht in `spec/type-mapping.md`,
 * Abschnitt 4.
 *
 * **Eine Quotierung, nicht zwei.** Bezeichner kommen in Backticks, Literale in
 * `'…'` — unabhaengig von `sql_mode`. Das ist gemessen und nicht angenommen,
 * siehe [appendDoubleQuoted].
 *
 * **Zwei Ebenen.** `information_schema` liefert den Text ein zweites Mal
 * escapet, als waere er selbst ein String-Literal: aus `'a\\b'` (Wert `a\b`)
 * wird `\'a\\\\b\'` (gemessen an MySQL 9.7.2, beide Spalten). Erkennbar ist
 * das daran, dass **jedes** `'` hinter einer ungeraden und jeder andere
 * Backslash in einer geraden Zahl von Backslashes steht; nur dann wird diese
 * Ebene abgezogen. Ein Text, der die Form nicht traegt, gilt als bereits
 * ausgepackt.
 */
internal object MysqlServerExpressionText {

    fun normalize(raw: String): String = neutralize(unescapeCatalogText(raw))

    /**
     * Die zweite Escape-Ebene von `information_schema` — abgezogen, wenn der
     * Text sie durchgehend traegt, sonst unveraendert.
     */
    private fun unescapeCatalogText(raw: String): String {
        if (!isCatalogEscaped(raw)) return raw
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            if (raw[index] == '\\' && index + 1 < raw.length) {
                out.append(raw[index + 1])
                index += 2
            } else {
                out.append(raw[index])
                index++
            }
        }
        return out.toString()
    }

    private fun isCatalogEscaped(raw: String): Boolean {
        var index = 0
        var sawEscape = false
        while (index < raw.length) {
            when (raw[index]) {
                '\\' -> {
                    var run = 0
                    while (index < raw.length && raw[index] == '\\') {
                        run++
                        index++
                    }
                    val quoted = index < raw.length && raw[index] == '\''
                    if (quoted) {
                        if (run % 2 == 0) return false
                        index++
                    } else if (run % 2 != 0) {
                        return false
                    }
                    sawEscape = true
                }
                // Ein `'` ohne Backslash davor: der Text traegt die Ebene nicht.
                '\'' -> return false
                else -> index++
            }
        }
        return sawEscape
    }

    /** Der ausgepackte Servertext, Zeichen fuer Zeichen in die neutrale Form. */
    private fun neutralize(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            index = when {
                char == '\'' -> appendLiteral(text, index, out)
                char == '"' -> appendDoubleQuoted(text, index, out)
                char == '`' -> appendIdentifier(text, index, out)
                // Der Introducer faellt weg; das Literal dahinter liest der
                // naechste Durchlauf.
                else -> introducerEnd(text, index) ?: run {
                    out.append(char)
                    index + 1
                }
            }
        }
        return out.toString()
    }

    /**
     * Ein String-Literal ab [start] als neutrales Literal (`''` als einziges
     * Escape). Rueckgabe: der Index dahinter; bei unterminiertem Literal steht
     * der Rest unveraendert im Ergebnis.
     */
    private fun appendLiteral(text: String, start: Int, out: StringBuilder): Int {
        val quote = text[start]
        val value = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            val char = text[index]
            when {
                char == '\\' && index + 1 < text.length -> {
                    value.append(unescape(text[index + 1]))
                    index += 2
                }
                char == quote && text.getOrNull(index + 1) == quote -> {
                    value.append(quote)
                    index += 2
                }
                char == quote -> {
                    out.append('\'').append(value.toString().replace("'", "''")).append('\'')
                    return index + 1
                }
                else -> {
                    value.append(char)
                    index++
                }
            }
        }
        out.append(text, start, text.length)
        return text.length
    }

    /**
     * Ein `"…"`-Lauf ab [start] — **wortgleich** uebernommen. Rueckgabe: der
     * Index dahinter.
     *
     * **Gemessen** (MySQL 9.7.2 und 8.0.46, `information_schema`): die drei
     * Ausdrucksfelder drucken Bezeichner **immer** in Backticks und
     * Zeichenketten **immer** in `'…'` — auch wenn `ANSI_QUOTES` gesetzt ist,
     * und auch wenn die Tabelle unter `ANSI_QUOTES` angelegt wurde. Selbst
     * `SHOW CREATE TABLE` unter `ANSI_QUOTES`, das Tabellen- und Spaltennamen
     * mit `"` schreibt, laesst die Ausdruecke darin in Backticks. Ein `"` an
     * dieser Stelle ist also nicht die Form, die dieser Server erzeugt.
     *
     * Trifft der Leser trotzdem eines (eine andere Version, eine
     * handgeschriebene Fixture), gilt die **neutrale** Lesart: `"…"` ist ein
     * Bezeichner, mit `""` als Escape — genau die Schreibweise, die MySQL
     * unter `ANSI_QUOTES` selbst verwendet. Der Lauf wird deshalb wortgleich
     * uebernommen, nicht umgedeutet. Ihn als Zeichenkette zu lesen (so tat es
     * die Vorform) machte aus einem Spaltenbezug still eine Konstante; der
     * umgekehrte Irrtum ist ausgeschlossen, weil der Server eine Zeichenkette
     * nie mit `"` druckt.
     */
    private fun appendDoubleQuoted(text: String, start: Int, out: StringBuilder): Int {
        var index = start + 1
        while (index < text.length) {
            if (text[index] != '"') {
                index++
            } else if (text.getOrNull(index + 1) == '"') {
                index += 2
            } else {
                out.append(text, start, index + 1)
                return index + 1
            }
        }
        out.append(text, start, text.length)
        return text.length
    }

    /** Die beiden Steuerzeichen, die in Kotlin-Quelltext keine Escape-Folge haben. */
    private val NUL = Char(0).toString()
    private val SUB = Char(0x1A).toString()

    /**
     * Die Escapes, die MySQL in einem String-Literal kennt. Ein Steuerzeichen
     * behaelt seinen Wert: das neutrale Literal traegt das Zeichen selbst, wie
     * es die uebrigen vier Dialekte schreiben.
     */
    private fun unescape(char: Char): String = when (char) {
        '0' -> NUL
        'b' -> "\b"
        'n' -> "\n"
        'r' -> "\r"
        't' -> "\t"
        'Z' -> SUB
        // `\%` und `\_` behalten den Backslash: MySQL gibt sie dem
        // LIKE-Muster weiter, und im Standard steht dort dasselbe Zeichenpaar.
        '%' -> "\\%"
        '_' -> "\\_"
        else -> char.toString()
    }

    /** `` `Name` `` als neutraler Bezeichner; Rueckgabe: der Index dahinter. */
    private fun appendIdentifier(text: String, start: Int, out: StringBuilder): Int {
        val name = StringBuilder()
        var index = start + 1
        while (index < text.length) {
            when {
                text[index] == '`' && text.getOrNull(index + 1) == '`' -> {
                    name.append('`')
                    index += 2
                }
                text[index] == '`' -> {
                    out.append(NeutralExpressionIdentifier.of(name.toString()))
                    return index + 1
                }
                else -> {
                    name.append(text[index])
                    index++
                }
            }
        }
        out.append(text, start, text.length)
        return text.length
    }

    private fun isNameChar(char: Char?): Boolean =
        char != null && (char.isLetterOrDigit() || char == '_' || char == '$')

    /**
     * Der Index hinter einem Zeichensatz-Introducer an [start] (`_latin1'`),
     * oder `null`, wenn dort keiner steht. Ein `_` mitten in einem Namen
     * zaehlt nicht, und hinter dem Introducer muss unmittelbar ein Literal
     * beginnen — so schreibt der Server ihn.
     */
    private fun introducerEnd(text: String, start: Int): Int? {
        if (text[start] != '_') return null
        if (isNameChar(text.getOrNull(start - 1))) return null
        var index = start + 1
        while (index < text.length && text[index].isLetterOrDigit()) index++
        if (index == start + 1) return null
        return if (text.getOrNull(index) == '\'') index else null
    }
}
