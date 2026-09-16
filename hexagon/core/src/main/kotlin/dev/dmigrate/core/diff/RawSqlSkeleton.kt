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
 * **Ausgenommen** sind Schluesselwoerter ([SqlKeywords]): `"user"` ist eine
 * Spalte, `user` die Funktion. Sie bleiben Platzhalter, in **einer**
 * Quotierung (`"user"`), damit `[user]` und `` `user` `` ihnen gleichen.
 *
 * `"…"` gilt dabei immer als Bezeichner. MySQL (ohne `ANSI_QUOTES`) und
 * SQLite (als Rueckfall) lesen es je nach Lage als String — das ist eine
 * Grenze dieser Faltung, keine Regel, die sie kennt.
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
         * - ein Kommentarzeichen ausserhalb eines Literals (`--`, der Anfang
         *   eines Blockkommentars oder MySQLs `#`): wer den Zeilenumbruch zu
         *   einem Leerzeichen macht, kommentiert den Rest der Bedingung aus.
         *   `#` ist in PostgreSQL zugleich XOR — der Rueckzug kostet dort nur
         *   die Faltung;
         * - Dollar-Quoting (`$$…$$`, `$tag$…$tag$`, auch mit Buchstaben
         *   ausserhalb von ASCII im Tag): eine Zeichenkette, die der Scanner
         *   nicht als solche erkennt;
         * - Oracles alternative Quotierung (`q'[…]'`, `nq'…'`): ihr
         *   Schlusszeichen ist ein anderes;
         * - ein Backslash: ob er ein Anfuehrungszeichen escapet, haengt am
         *   Dialekt (MySQL ja, PostgreSQL nur in `E'…'`);
         * - ein `[` nach Leerraum hinter einem Namen, einer Klammer oder einem
         *   Platzhalter: in PostgreSQL ein Index (`tags [pos]`), in T-SQL
         *   Quoting (`[orders] [o]`). Es gilt als Quoting, wenn der Text an
         *   anderer Stelle **eindeutiges** Bracket-Quoting traegt (ein `[` am
         *   Anfang, hinter einem Operator, Komma, einer oeffnenden Klammer oder
         *   einem Schluesselwort — dort ist es in PostgreSQL, MySQL und Oracle
         *   gar keine Syntax); sonst zieht sich die Faltung zurueck;
         * - eine nicht geschlossene Quotierung.
         */
        fun of(sql: String): RawSqlSkeleton? {
            if (sql.any { it in UNSCANNABLE }) return null
            val scanner = Scanner(sql)
            val skeleton = scanner.scan() ?: return null
            if (scanner.ambiguousBracket && !scanner.quotingBracket) return null
            if (UNCERTAIN.containsMatchIn(skeleton)) return null
            return RawSqlSkeleton(skeleton, scanner.protected)
        }

        /** Ein Backslash — und die eigenen Platzhalterzeichen, falls der Text sie schon traegt. */
        private val UNSCANNABLE = setOf('\\', LITERAL, IDENTIFIER, CLOSE)

        /** Was ausser einem Namen vor `[` steht, wenn die Klammer ein Index ist und kein Quoting. */
        private val SUBSCRIPT_BASE = setOf(']', ')', CLOSE)

        private val PLACEHOLDER = Regex("([\uE000\uE002])(\\d+)\uE001")

        /** Ein Tag-Zeichen wie in PostgreSQL: ASCII-Buchstabe, `_` oder jedes Zeichen ausserhalb von ASCII. */
        private const val TAG = "A-Za-z_\\x{80}-\\x{DFFF}\\x{E003}-\\x{10FFFF}"

        private val UNCERTAIN = Regex("--|/\\*|#|\\$(?:[$TAG][${TAG}0-9]*)?\\$")

        /** Das Praefix der alternativen Quotierung Oracles, direkt vor dem `'`. */
        private val ORACLE_Q_PREFIX = Regex("(?:^|[^${SqlLexis.NAME_CHARS}])[nN]?[qQ]$")
    }

    /**
     * Ein Durchlauf von links nach rechts. Er kennt nur Quotierungen — keine
     * Grammatik: was er nicht sicher abgrenzt, meldet er als `null`.
     */
    private class Scanner(private val sql: String) {
        val protected = mutableListOf<String>()
        private val out = StringBuilder()
        private var position = 0

        /** Ein `[`, das nur in T-SQL Quoting ist, in PostgreSQL ein Index. */
        var ambiguousBracket = false
            private set

        /** Ein `[`, das in keinem Dialekt etwas anderes als Quoting sein kann. */
        var quotingBracket = false
            private set

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
            if (ORACLE_Q_PREFIX.containsMatchIn(out.takeLast(ORACLE_PREFIX_WINDOW))) return false
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
         * Array (`data[0]`, `ARRAY[…]`) und bleibt Syntax. Steht Leerraum
         * dazwischen, liest PostgreSQL es genauso (`tags [pos]`), T-SQL als
         * Quoting — sicher ist es nur hinter einem Schluesselwort (Quoting)
         * und hinter `ARRAY` (Array); sonst zieht sich die Faltung zurueck.
         */
        private fun bracket(): Boolean {
            when (bracketReading()) {
                BracketReading.SUBSCRIPT -> {
                    out.append('[')
                    position++
                    return true
                }
                BracketReading.QUOTING -> quotingBracket = true
                // Vorlaeufig als Quoting gelesen; ob das traegt, entscheidet
                // `of` am Ende des Textes.
                BracketReading.AMBIGUOUS -> ambiguousBracket = true
            }
            return quotedIdentifier(']')
        }

        /** Wie `[` hier zu lesen ist. */
        private fun bracketReading(): BracketReading {
            val previous = out.lastOrNull() ?: return BracketReading.QUOTING
            if (SqlLexis.isNameChar(previous) || previous in SUBSCRIPT_BASE) return BracketReading.SUBSCRIPT
            if (SqlLexis.WHITESPACE_CHARS.indexOf(previous) < 0) return BracketReading.QUOTING
            val before = out.trimEnd { SqlLexis.WHITESPACE_CHARS.indexOf(it) >= 0 }
            val significant = before.lastOrNull() ?: return BracketReading.QUOTING
            if (significant in SUBSCRIPT_BASE) return BracketReading.AMBIGUOUS
            if (!SqlLexis.isNameChar(significant)) return BracketReading.QUOTING
            val word = SqlLexis.wordBefore(before, before.length)
            return when {
                word.equals("array", ignoreCase = true) -> BracketReading.SUBSCRIPT
                SqlKeywords.isKeyword(word) -> BracketReading.QUOTING
                else -> BracketReading.AMBIGUOUS
            }
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
            when {
                !SIMPLE_IDENTIFIER.matches(content) -> placeholder(IDENTIFIER, raw)
                SqlKeywords.isKeyword(content) -> placeholder(IDENTIFIER, "\"$content\"")
                else -> out.append(content)
            }
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

/** Wie der Scanner ein `[` liest. */
private enum class BracketReading { QUOTING, SUBSCRIPT, AMBIGUOUS }

/** Wie weit vor einem `'` nach Oracles `q`/`nq` gesucht wird — Praefix plus ein Zeichen davor. */
private const val ORACLE_PREFIX_WINDOW = 3
