package dev.dmigrate.core.diff.migration

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
 * **Zwei Schreibweisen, dieselbe Aussage.** Geschrieben wird die Liste als
 * `spalte IN ('a','b')` — zurueck kommt sie so aber nicht unbedingt: SQL Server
 * speichert den Ausdruck normalisiert als `spalte='b' OR spalte='a'`, mit
 * eigener Reihenfolge (live gemessen). Beide Formen werden deshalb erkannt,
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
internal object EnumCheckProjection {

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
        return inListValues(text, column) ?: equalityChainValues(text, column)
    }

    /** `spalte IN ('a', 'b')` — die Form, die d-migrate selbst schreibt. */
    private fun inListValues(text: String, column: String): List<String>? {
        val afterColumn = stripLeadingIdentifier(text, column) ?: return null
        val afterIn = stripLeadingKeyword(afterColumn, "IN") ?: return null
        if (!afterIn.startsWith("(")) return null
        val closing = afterIn.lastIndexOf(')')
        if (closing != afterIn.length - 1) return null
        return parseStringList(afterIn.substring(1, closing))
    }

    /**
     * `spalte='a' OR spalte='b'` — die Form, in der SQL Server dieselbe
     * Aussage zurueckliefert.
     */
    private fun equalityChainValues(text: String, column: String): List<String>? {
        val values = mutableListOf<String>()
        for (term in splitTopLevelOr(text) ?: return null) {
            val trimmed = unwrapOuterParens(term.trim())
            val afterColumn = stripLeadingIdentifier(trimmed, column) ?: return null
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
            var depth = 0
            var closesAtEnd = true
            for ((i, ch) in current.withIndex()) {
                if (ch == '(') depth++
                if (ch == ')') depth--
                if (depth == 0 && i < current.length - 1) {
                    closesAtEnd = false
                    break
                }
            }
            if (!closesAtEnd) return current
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
        if (rest.isNotEmpty() && !rest.first().isWhitespace() && !rest.startsWith("(")) return null
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
            while (i < body.length && body[i].isWhitespace()) i++
            if (i >= body.length) break
            if (body[i] != ',') return null
            i++
        }
        return values.takeIf { it.isNotEmpty() }
    }
}
