package dev.dmigrate.core.diff

import dev.dmigrate.core.model.ConstraintDefinition
import dev.dmigrate.core.model.ConstraintType
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.core.model.TableDefinition

/**
 * Erkennt einen CHECK, der den erlaubten Wertevorrat **einer** Spalte
 * aufzaehlt — die Gestalt, in der ein Enum in Dialekten ohne eigenen Enum-Typ
 * in der Datenbank landet.
 *
 * Gebraucht wird das im Fingerprint: authored steht ein `enum(werte)` an der
 * Spalte, zurueckgelesen eine Textspalte plus genau dieser Constraint. Ohne
 * eine gemeinsame Form koennen die beiden Seiten nicht gleich hashen (siehe
 * `docs/planning/done/fingerprint-v8-enum-check-projection.md`).
 *
 * **Dieselbe Aussage, je Server anders aufgeschrieben.** Geschrieben wird die
 * Liste als `spalte IN ('a','b')` — zurueck kommt sie so nur bei SQLite und
 * Oracle. Live gemessen:
 *
 * | Server | was er aus `mood IN ('red','green')` macht |
 * | --- | --- |
 * | SQLite, Oracle | der Text, wie geschrieben |
 * | SQL Server | `mood='green' OR mood='red'` — umsortiert |
 * | PostgreSQL | `((mood = ANY (ARRAY['red'::text, 'green'::text])))` |
 *
 * PostgreSQL setzt zusaetzlich Typ-Casts an jedes Literal, castet bei einer
 * `varchar`-Spalte auch die **Spalte** (`(shade)::text`) und schreibt eine
 * einelementige Liste als `=` statt `IN`. Alle diese Formen werden erkannt,
 * und die Werte gelten als Menge.
 *
 * Die Erkennung ist **formbasiert, nicht namensbasiert**: PostgreSQL vergibt
 * den Constraint-Namen automatisch, SQL Server nach Konvention, und beide
 * duerfen fuer die Gleichheit keine Rolle spielen.
 *
 * Bewusst eng: was hier nicht passt, bleibt ein gewoehnlicher CHECK. Nicht
 * erkannt werden Ausdruecke ueber eine andere Spalte, `NOT IN`, Vergleiche mit
 * etwas anderem als String-Literalen, Verknuepfungen mit `AND`, und alles, was
 * nach dem Ausdruck noch weitergeht.
 */
object EnumCheckProjection {

    /**
     * Der Wertevorrat je Spalte, so wie die Spaltentypen der Tabelle ihn
     * fuehren. Sortiert, weil er hier nur zum Abgleich zweier Darstellungen
     * dient — der Typ selbst behaelt seine Reihenfolge, MySQLs nativer `ENUM`
     * hat Ordinal-Semantik.
     */
    fun declaredVocabulary(table: TableDefinition): Map<String, List<String>> =
        table.columns.mapNotNull { (colName, col) ->
            (col.type as? NeutralType.Enum)?.values?.let { colName to it.sorted() }
        }.toMap()

    /**
     * Welche CHECK-Constraints der Tabelle als Wertevorrat gelten, und fuer
     * welche Spalte mit welchen Werten.
     *
     * **Vor** dem Falten bestimmt, damit das Ergebnis nicht von der
     * Reihenfolge in `table.constraints` abhaengt. Zwei Faelle bleiben
     * ausdruecklich ungefaltet:
     *
     * - **Mehr als ein passender CHECK auf derselben Spalte.** Dann ist nicht
     *   entscheidbar, welcher den Wertevorrat beschreibt — und zwei Constraints
     *   in eine Projektion zu falten liesse einen davon spurlos verschwinden,
     *   samt dem Unterschied, den er ausmacht.
     * - **Ein CHECK, der dem Spaltentyp widerspricht.** Ein handgeschriebener
     *   CHECK mit anderen Werten als das authored `enum` bleibt sichtbar.
     */
    fun foldable(table: TableDefinition): Map<ConstraintDefinition, Pair<String, List<String>>> {
        val declared = declaredVocabulary(table)
        val candidates = mutableListOf<Pair<ConstraintDefinition, Pair<String, List<String>>>>()
        for (c in table.constraints) {
            if (c.type != ConstraintType.CHECK) continue
            for (colName in table.columns.keys) {
                val values = valuesOf(c.expression, colName)?.sorted() ?: continue
                candidates += c to (colName to values)
                break
            }
        }
        val perColumn = candidates.groupBy { it.second.first }
        return candidates
            .filter { (_, hit) ->
                val (colName, values) = hit
                val fromType = declared[colName]
                perColumn.getValue(colName).size == 1 && (fromType == null || fromType == values)
            }
            .toMap()
    }

    /**
     * Der Wertevorrat je Spalte, gleich in welcher Darstellung er vorliegt —
     * am Spaltentyp oder als eigener CHECK.
     *
     * Das ist die gemeinsame Form, auf die beide Seiten eines Vergleichs
     * gebracht werden: eine Textspalte mit ihrem `IN`-CHECK und eine
     * `enum`-Spalte sagen dasselbe, und nur so faellt der Unterschied
     * zwischen den WERTEN auf statt der zwischen den Schreibweisen.
     */
    fun vocabulary(table: TableDefinition): Map<String, List<String>> {
        val result = declaredVocabulary(table).toMutableMap()
        for ((colName, values) in foldable(table).values) {
            result[colName] = values
        }
        return result
    }

    /**
     * Die Werte, wenn [expression] den Wertevorrat von [column] aufzaehlt —
     * sonst `null`.
     *
     * Das Parsen ist literal-bewusst: ein Komma oder ein `OR` **in** einem Wert
     * darf den Ausdruck nicht zerlegen, und `''` ist ein escapetes Hochkomma,
     * kein Literal-Ende.
     */
    fun valuesOf(expression: String?, column: String): List<String>? {
        val text = unwrapOuterParens(expression?.trim() ?: return null)
        return inListValues(text, column)
            ?: anyArrayValues(text, column)
            ?: equalityChainValues(text, column)
    }

    /**
     * Der Wertevorrat als Text, in genau einer Schreibweise.
     *
     * Gebraucht, wo der Ausdruck als Text im Vergleich bleibt, statt in die
     * Spalte gefaltet zu werden: die beiden Seiten schreiben dieselbe Aussage
     * verschieden auf (`IN`-Liste hier, OR-Kette dort, je eigene Reihenfolge),
     * und ein Textvergleich liest das als Unterschied. Das Ergebnis ist wieder
     * eine Form, die [valuesOf] erkennt — die Faltung greift danach unveraendert.
     */
    fun canonicalText(column: String, values: List<String>): String {
        val literals = values.sorted().joinToString(", ") { "'" + it.replace("'", "''") + "'" }
        return "$column IN ($literals)"
    }

    /** `spalte IN ('a', 'b')` — die Form, die d-migrate selbst schreibt. */
    private fun inListValues(text: String, column: String): List<String>? {
        val afterColumn = stripLeadingColumnRef(text, column) ?: return null
        val afterIn = stripLeadingKeyword(afterColumn, "IN") ?: return null
        if (!afterIn.startsWith("(")) return null
        val closing = afterIn.lastIndexOf(')')
        if (closing != afterIn.length - 1) return null
        return parseStringList(afterIn.substring(1, closing))
    }

    /**
     * `spalte = ANY (ARRAY['a'::text, 'b'::text])` — die Form, in die
     * PostgreSQL eine `IN`-Liste beim Speichern umschreibt.
     */
    private fun anyArrayValues(text: String, column: String): List<String>? {
        val afterColumn = stripLeadingColumnRef(text, column) ?: return null
        if (!afterColumn.startsWith("=")) return null
        val afterAny = stripLeadingKeyword(afterColumn.substring(1).trimStart(), "ANY") ?: return null
        if (!afterAny.startsWith("(") || !afterAny.endsWith(")")) return null
        val inner = afterAny.substring(1, afterAny.length - 1).trim()
        val afterArray = stripLeadingKeyword(inner, "ARRAY") ?: return null
        if (!afterArray.startsWith("[") || !afterArray.endsWith("]")) return null
        return parseStringList(afterArray.substring(1, afterArray.length - 1))
    }

    /**
     * `spalte='a' OR spalte='b'` — die Form, in der SQL Server dieselbe
     * Aussage zurueckliefert.
     */
    private fun equalityChainValues(text: String, column: String): List<String>? {
        val values = mutableListOf<String>()
        for (term in splitTopLevelOr(text) ?: return null) {
            val trimmed = unwrapOuterParens(term.trim())
            val afterColumn = stripLeadingColumnRef(trimmed, column) ?: return null
            if (!afterColumn.startsWith("=")) return null
            val literal = parseStringList(afterColumn.substring(1).trim())?.singleOrNull() ?: return null
            values += literal
        }
        return values.takeIf { it.isNotEmpty() }
    }

    /**
     * Traegt aeussere Klammern ab — aber nur, wenn sie wirklich zusammengehoeren.
     * Blindes Abschneiden des ersten und letzten Zeichens verstuemmelt
     * `(mood='a') OR (mood='b')` zu etwas Unlesbarem.
     */
    private fun unwrapOuterParens(text: String): String {
        var current = text.trim()
        while (current.startsWith("(") && current.endsWith(")")) {
            if (matchingParen(current) != current.length - 1) return current
            current = current.substring(1, current.length - 1).trim()
        }
        return current
    }

    /** Zerlegt an `OR`, aber nicht innerhalb von String-Literalen. */
    private fun splitTopLevelOr(text: String): List<String>? {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        var inLiteral = false
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\'') {
                inLiteral = !inLiteral
                current.append(ch)
                i++
                continue
            }
            if (!inLiteral && matchesKeyword(text, i, "OR")) {
                parts += current.toString()
                current.clear()
                i += 2
                continue
            }
            // Ein `AND` macht aus der Aufzaehlung etwas anderes.
            if (!inLiteral && matchesKeyword(text, i, "AND")) return null
            current.append(ch)
            i++
        }
        if (inLiteral) return null
        parts += current.toString()
        return parts
    }

    /** Ein Schluesselwort zaehlt nur, wenn links und rechts kein Wortzeichen steht. */
    private fun matchesKeyword(text: String, at: Int, keyword: String): Boolean {
        if (!text.regionMatches(at, keyword, 0, keyword.length, ignoreCase = true)) return false
        val before = text.getOrNull(at - 1)
        val after = text.getOrNull(at + keyword.length)
        return !isWordChar(before) && !isWordChar(after)
    }

    private fun isWordChar(ch: Char?): Boolean = ch != null && (ch.isLetterOrDigit() || ch == '_')

    /**
     * Ob nach einem Schluesselwort dessen Argument beginnt — durch Abstand
     * getrennt, oder direkt als Klammer (`IN(...)`) bzw. eckige Klammer
     * (`ARRAY[...]`).
     */
    private fun startsArgument(rest: String): Boolean =
        rest.first().isWhitespace() || rest.startsWith("(") || rest.startsWith("[")

    /**
     * Entfernt den Spaltenbezug am Anfang, samt allem, was ein Server um ihn
     * herum schreibt: eine Klammer und einen Typ-Cast.
     *
     * PostgreSQL castet die Spalte, wenn ihr Typ nicht schon der des Literals
     * ist — aus `shade IN ('a')` auf einer `varchar`-Spalte wird
     * `(shade)::text = 'a'::text`. Ohne diesen Schritt liefe die Erkennung
     * genau an den Spalten vorbei, die eine Laengenbegrenzung tragen.
     */
    private fun stripLeadingColumnRef(text: String, column: String): String? {
        val direct = stripLeadingIdentifier(text, column)
        val rest = direct ?: stripParenthesizedIdentifier(text, column) ?: return null
        return stripCast(rest)
    }

    /** `(spalte)` — die Klammer, die PostgreSQL vor einen Cast setzt. */
    private fun stripParenthesizedIdentifier(text: String, column: String): String? {
        if (!text.startsWith("(")) return null
        val closing = matchingParen(text) ?: return null
        val inner = text.substring(1, closing).trim()
        if (stripLeadingIdentifier(inner, column) != "") return null
        return text.substring(closing + 1).trimStart()
    }

    /**
     * Ein `::typ`-Suffix, falls eines dasteht. Der Typname kann aus mehreren
     * Woertern bestehen (`character varying`) und eine Laenge tragen.
     */
    private fun stripCast(text: String): String {
        if (!text.startsWith("::")) return text
        var i = 2
        while (i < text.length && isTypeNameChar(text[i])) i++
        if (i < text.length && text[i] == '(') {
            val closing = matchingParen(text, i) ?: return text.substring(i).trimStart()
            i = closing + 1
        }
        return text.substring(i).trimStart()
    }

    /** Woraus ein Typname besteht — mehrwortig erlaubt (`character varying`). */
    private fun isTypeNameChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_' || ch == ' '

    /** Die Position der Klammer, die die bei [from] schliesst — literal-bewusst. */
    private fun matchingParen(text: String, from: Int = 0): Int? {
        var depth = 0
        var inLiteral = false
        for (i in from until text.length) {
            val ch = text[i]
            if (ch == '\'') {
                inLiteral = !inLiteral
                continue
            }
            if (inLiteral) continue
            if (ch == '(') depth++
            if (ch == ')') {
                depth--
                if (depth == 0) return i
            }
        }
        return null
    }

    /**
     * Entfernt den Spaltenbezug am Anfang — mit oder ohne Quoting. Der Reverse
     * liefert je nach Dialekt `mood`, `"mood"`, `[mood]` oder `` `mood` ``.
     */
    private fun stripLeadingIdentifier(text: String, column: String): String? {
        val candidates = listOf(column, "\"$column\"", "[$column]", "`$column`")
        val match = candidates.firstOrNull { text.startsWith(it) } ?: return null
        val rest = text.substring(match.length)
        // Ohne Trenner waere `moody='x'` ein Treffer fuer die Spalte `mood`.
        if (isWordChar(rest.firstOrNull())) return null
        return rest.trimStart()
    }

    private fun stripLeadingKeyword(text: String, keyword: String): String? {
        if (!text.regionMatches(0, keyword, 0, keyword.length, ignoreCase = true)) return null
        val rest = text.substring(keyword.length)
        if (rest.isNotEmpty() && !startsArgument(rest)) return null
        return rest.trimStart()
    }

    /** Eine Folge von String-Literalen, durch Kommata getrennt. */
    private fun parseStringList(body: String): List<String>? {
        val values = mutableListOf<String>()
        var i = 0
        while (i < body.length) {
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length || body[i] != '\'') return null
            val sb = StringBuilder()
            i++
            while (true) {
                if (i >= body.length) return null
                val ch = body[i]
                if (ch == '\'') {
                    // Verdoppeltes Hochkomma ist ein Wert-Zeichen, kein Ende.
                    if (i + 1 < body.length && body[i + 1] == '\'') {
                        sb.append('\'')
                        i += 2
                        continue
                    }
                    i++
                    break
                }
                sb.append(ch)
                i++
            }
            values += sb.toString()
            // `'red'::text` — PostgreSQL haengt den Typ an jedes Literal.
            val afterCast = stripCast(body.substring(i))
            i = body.length - afterCast.length
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length) break
            if (body[i] != ',') return null
            i++
        }
        return values.takeIf { it.isNotEmpty() }
    }
}
