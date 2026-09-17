package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.IndexColumn
import dev.dmigrate.driver.DatabaseDialect
import dev.dmigrate.driver.SqlIdentifiers
import dev.dmigrate.driver.renderKey

/**
 * Schreibt einen rohen neutralen Ausdruck (CHECK, Berechnungsausdruck,
 * Ausdrucks-Schluessel eines Index) so, wie MySQL ihn lesen muss.
 *
 * Im neutralen Modell gelten die lexikalischen Regeln des SQL-Standards:
 * `"…"` ist ein Bezeichner, und ein Backslash in `'…'` ist ein gewoehnliches
 * Zeichen. MySQL liest beides anders — ohne `ANSI_QUOTES` ist `"…"` eine
 * Zeichenkette, und ohne `NO_BACKSLASH_ESCAPES` escapet der Backslash das
 * Folgezeichen. Ein unveraendert gerendertes `CHECK ("Qty" > 0)` vergliche
 * auf MySQL also still eine Zeichenkette mit einer Zahl. Deshalb, und nur
 * deshalb, schreibt der MySQL-Generator hier um:
 *
 * - `"Name"` wird `` `Name` `` (`""` ist das Escape fuer `"`);
 * - ein Backslash in einem String-Literal wird verdoppelt — dieselbe Regel, nach
 *   der [SqlIdentifiers.quoteStringLiteral] MySQL-Literale schreibt;
 * - ein **nacktes** Wort, das MySQL reserviert, bekommt Backticks
 *   ([MysqlReservedWords]). Das neutrale Modell laesst einen rein
 *   kleingeschriebenen Namen unquotiert, und `` `key` `` eines MySQL-Reverse
 *   kaeme sonst als `key` zurueck an den Server: `CHECK (key > 0)` ist dort
 *   `ERROR 1064` (gemessen, 9.7.2).
 *
 * Alles andere bleibt wortgleich: Backtick-Bezeichner, Kommentare und der
 * Inhalt eines Literals (bis auf den Backslash). Kann der Scanner den Text
 * nicht sicher abgrenzen (eine nicht geschlossene Quotierung oder ein nicht
 * geschlossener Blockkommentar), gibt er ihn unveraendert zurueck — ein falsch
 * gesetztes Anfuehrungszeichen waere schlimmer als ein fehlendes.
 *
 * **Was der Rueckfall kostet.** Ein so nicht abgrenzbarer Text ist im
 * neutralen Modell fehlerhaft, und jedes andere Ziel lehnt ihn ab. MySQL nicht
 * unbedingt: seine **eigene** Escape-Schreibweise (`'it\'s'`) ist unter
 * Standardregeln nicht abgrenzbar, fuer MySQL aber gueltig. Ein solcher Text
 * wird wortgleich gerendert, und MySQL liest ihn nach seinen Regeln — ein
 * `"…"` darin bleibt dort eine Zeichenkette. Das ist dieselbe Lesart, die vor
 * dieser Umschreibe-Regel fuer **jeden** Text galt; die Regel macht also
 * nichts still, was vorher laut war, sie erreicht diesen einen Fall nur nicht.
 * Ein Reverse erzeugt ihn nie (er schreibt die neutrale Form), eine von Hand
 * geschriebene Datei kann ihn tragen.
 *
 * Die Regel steht in `spec/ddl-generation-rules.md`, „Roher Ausdruckstext".
 */
internal object MysqlRawExpressionText {

    fun toMysql(expression: String): String = Scanner(expression).render() ?: expression

    private class Scanner(private val sql: String) {
        private val out = StringBuilder(sql.length + 8)
        private var position = 0

        /**
         * Das zuletzt gelesene Wort, solange nur Leerraum dazwischenstand —
         * die Stellung „unmittelbar hinter `AS`" (Typname eines `CAST`).
         */
        private var previousWord: String? = null

        fun render(): String? {
            while (position < sql.length) {
                val char = sql[position]
                val ok = when {
                    char == '\'' -> forgetWord { literal() }
                    char == '"' -> forgetWord { doubleQuotedIdentifier() }
                    char == '`' -> forgetWord { verbatimUntilClosing('`') }
                    sql.startsWith("--", position) -> lineComment()
                    sql.startsWith("/*", position) -> blockComment()
                    isWordStart(char) || char.isDigit() -> wordOrNumber(char)
                    else -> {
                        if (!char.isWhitespace()) previousWord = null
                        out.append(char)
                        position++
                        true
                    }
                }
                if (!ok) return null
            }
            return out.toString()
        }

        /**
         * Ein Wort- oder Zahlenlauf. Quotiert wird nur ein Wort, das MySQL
         * reserviert und das hier kein Bezeichner sein kann: nicht vor `(`
         * (Funktionsaufruf) und nicht hinter `AS` (Typname).
         */
        private fun wordOrNumber(first: Char): Boolean {
            var end = position
            while (end < sql.length && isWordPart(sql[end])) end++
            val word = sql.substring(position, end)
            position = end
            val quote = isWordStart(first) &&
                previousWord?.lowercase() != "as" &&
                nextCodeChar() != '(' &&
                MysqlReservedWords.mustQuoteAsIdentifier(word)
            out.append(if (quote) SqlIdentifiers.quoteIdentifier(word, DatabaseDialect.MYSQL) else word)
            previousWord = word
            return true
        }

        /** Ein Token, hinter dem kein `AS` mehr steht. */
        private inline fun forgetWord(read: () -> Boolean): Boolean {
            previousWord = null
            return read()
        }

        /** Das naechste Zeichen hinter dem Leerraum ab [position], oder `null`. */
        private fun nextCodeChar(): Char? {
            var index = position
            while (index < sql.length && sql[index].isWhitespace()) index++
            return sql.getOrNull(index)
        }

        /** `'…'` mit `''` als Escape; der Backslash darin wird verdoppelt. */
        private fun literal(): Boolean {
            val end = closing('\'') ?: return false
            out.append(sql.substring(position, end + 1).replace("\\", "\\\\"))
            position = end + 1
            return true
        }

        private fun doubleQuotedIdentifier(): Boolean {
            val end = closing('"') ?: return false
            val name = sql.substring(position + 1, end).replace("\"\"", "\"")
            out.append(SqlIdentifiers.quoteIdentifier(name, DatabaseDialect.MYSQL))
            position = end + 1
            return true
        }

        private fun verbatimUntilClosing(quote: Char): Boolean {
            val end = closing(quote) ?: return false
            out.append(sql, position, end + 1)
            position = end + 1
            return true
        }

        private fun lineComment(): Boolean {
            val end = sql.indexOf('\n', position).let { if (it < 0) sql.length else it }
            out.append(sql, position, end)
            position = end
            return true
        }

        private fun blockComment(): Boolean {
            val close = sql.indexOf("*/", position + 2)
            if (close < 0) return false
            out.append(sql, position, close + 2)
            position = close + 2
            return true
        }

        /**
         * Der Index des schliessenden [quote] zur Quotierung an [position];
         * ein verdoppeltes Zeichen ist sein Escape. `null`, wenn sie nicht
         * schliesst.
         */
        private fun closing(quote: Char): Int? {
            var index = position + 1
            while (index < sql.length) {
                if (sql[index] != quote) {
                    index++
                } else if (index + 1 < sql.length && sql[index + 1] == quote) {
                    index += 2
                } else {
                    return index
                }
            }
            return null
        }
    }
}

/** Womit ein MySQL-Bezeichner beginnen kann — keine Ziffer. */
private fun isWordStart(char: Char): Boolean = char.isLetter() || char == '_' || char == '$'

/** Woraus ein MySQL-Bezeichner besteht. */
private fun isWordPart(char: Char): Boolean = char.isLetterOrDigit() || char == '_' || char == '$'

/**
 * Ein Indexschluessel fuer MySQL: wie [renderKey], der Ausdruck aber in
 * MySQL-Schreibweise ([MysqlRawExpressionText]). Beide Index-Renderer (Generate
 * und Diff) gehen hierueber.
 */
internal fun IndexColumn.mysqlKey(quoteIdentifier: (String) -> String): String =
    copy(expression = expression?.let(MysqlRawExpressionText::toMysql)).renderKey(quoteIdentifier)
