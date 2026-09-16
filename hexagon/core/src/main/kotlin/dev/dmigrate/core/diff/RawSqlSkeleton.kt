package dev.dmigrate.core.diff

/**
 * Ein roher SQL-Text als **Geruest**, ueber das die Schreibweise-Regeln von
 * `schema compare` laufen (ADR 0056).
 *
 * Im Geruest stehen String-Literale und nicht-einfache quotierte Bezeichner
 * (`"my col"`, `` `a b` ``, `[Order Details]`) als Platzhalter; was ein
 * Platzhalter vertritt, fasst keine Regel an — weder Leerraum noch Quoting
 * noch Operator-Folgen. Einfache quotierte Bezeichner (`"id"`, `` `id` ``,
 * `[id]`) stehen entpackt darin: ihr Quoting ist Dialekt-Schreibweise.
 *
 * Der Platzhalter kommt aus der Private Use Area und enthaelt **kein**
 * Leerzeichen — sonst zerstoerte ihn die Leerraum-Regel, und die Literale
 * gingen verloren (so einmal geschehen: `'a~~b'` galt als `'a like b'`).
 */
internal class RawSqlSkeleton private constructor(
    /** Das Geruest; nur darauf laufen die Regeln. */
    val text: String,
    private val protected: List<String>,
) {

    /** Setzt die geschuetzten Stuecke in ein umgeformtes Geruest zurueck. */
    fun restore(folded: String): String =
        PLACEHOLDER.replace(folded) { protected[it.groupValues[2].toInt()] }

    companion object {
        /** Oeffnet den Platzhalter eines String-Literals. */
        const val LITERAL = '\uE000'

        /** Oeffnet den Platzhalter eines geschuetzten Bezeichners. */
        const val IDENTIFIER = '\uE002'

        /** Schliesst jeden Platzhalter. */
        const val CLOSE = '\uE001'

        /**
         * Das Geruest von [sql] — oder `null`, wenn ein lexikalischer Scanner
         * den Text nicht sicher abgrenzen kann. Dann wird das Feld **nicht**
         * gefaltet (ADR 0056, Rueckzug):
         *
         * - ein Kommentarzeichen ausserhalb eines Literals (`--` oder der
         *   Anfang eines Blockkommentars): wer den Zeilenumbruch zu einem
         *   Leerzeichen macht, kommentiert den Rest der Bedingung aus;
         * - Dollar-Quoting (`$$…$$`, `$tag$…$tag$`): eine Zeichenkette, die
         *   der Scanner nicht als solche erkennt;
         * - ein Backslash: ob er ein Anfuehrungszeichen escapet, haengt am
         *   Dialekt (MySQL ja, PostgreSQL nur in `E'…'`);
         * - eine nicht geschlossene Quotierung.
         */
        fun of(sql: String): RawSqlSkeleton? {
            if (sql.any { it in UNSCANNABLE }) return null
            val scanner = Scanner(sql)
            val skeleton = scanner.scan() ?: return null
            if (UNCERTAIN.containsMatchIn(skeleton)) return null
            return RawSqlSkeleton(skeleton, scanner.protected)
        }

        /** Ein Backslash — und die eigenen Platzhalterzeichen, falls der Text sie schon traegt. */
        private val UNSCANNABLE = setOf('\\', LITERAL, IDENTIFIER, CLOSE)

        /** Was vor `[` steht, wenn die Klammer ein Index ist und kein Quoting. */
        private val SUBSCRIPT_BASE = setOf('_', ']', ')', CLOSE)

        private val PLACEHOLDER = Regex("([\uE000\uE002])(\\d+)\uE001")

        private val UNCERTAIN = Regex("--|/\\*|\\$(?:[A-Za-z_][A-Za-z0-9_]*)?\\$")
    }

    /**
     * Ein Durchlauf von links nach rechts. Er kennt nur Quotierungen — keine
     * Grammatik: was er nicht sicher abgrenzt, meldet er als `null`.
     */
    private class Scanner(private val sql: String) {
        val protected = mutableListOf<String>()
        private val out = StringBuilder()
        private var position = 0

        fun scan(): String? {
            while (position < sql.length) {
                val ok = when (sql[position]) {
                    '\'' -> literal()
                    '"' -> quotedIdentifier('"')
                    '`' -> quotedIdentifier('`')
                    '[' -> bracket()
                    else -> {
                        out.append(sql[position])
                        position++
                        true
                    }
                }
                if (!ok) return null
            }
            return out.toString()
        }

        /** `'…'` mit `''` als Escape — immer geschuetzt. */
        private fun literal(): Boolean {
            val end = closing('\'', position + 1) ?: return false
            placeholder(LITERAL, sql.substring(position, end + 1))
            position = end + 1
            return true
        }

        /** `"…"`, `` `…` `` oder `[…]` mit verdoppeltem Schlusszeichen als Escape. */
        private fun quotedIdentifier(close: Char): Boolean {
            val end = closing(close, position + 1) ?: return false
            identifier(sql.substring(position, end + 1), sql.substring(position + 1, end))
            position = end + 1
            return true
        }

        /**
         * `[…]` ist T-SQL-Quoting — ausser direkt hinter einem Namen, einer
         * Klammer oder einem Platzhalter: dort ist es ein Index oder ein
         * Array (`data[0]`, `ARRAY[…]`) und bleibt Syntax.
         */
        private fun bracket(): Boolean {
            val previous = out.lastOrNull()
            val subscript = previous != null && (previous.isLetterOrDigit() || previous in SUBSCRIPT_BASE)
            if (subscript) {
                out.append('[')
                position++
                return true
            }
            return quotedIdentifier(']')
        }

        /** Index des schliessenden [quote]; ein verdoppeltes gilt als Escape. */
        private fun closing(quote: Char, from: Int): Int? {
            var index = from
            while (index < sql.length) {
                if (sql[index] == quote) {
                    if (index + 1 < sql.length && sql[index + 1] == quote) {
                        index += 2
                        continue
                    }
                    return index
                }
                index++
            }
            return null
        }

        private fun identifier(raw: String, content: String) {
            if (SIMPLE_IDENTIFIER.matches(content)) out.append(content) else placeholder(IDENTIFIER, raw)
        }

        private fun placeholder(kind: Char, raw: String) {
            out.append(kind).append(protected.size).append(CLOSE)
            protected += raw
        }
    }
}

/**
 * Ein **einfacher** Bezeichner: nur er wird entpackt. Die Schreibweise bleibt
 * dabei — `"Quantity"` und `quantity` sind danach weiterhin verschieden, in
 * PostgreSQL sind sie das auch.
 */
private val SIMPLE_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
