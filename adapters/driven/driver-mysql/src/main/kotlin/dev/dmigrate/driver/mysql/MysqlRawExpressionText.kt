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
 * **Drei Stellungen entscheidet der Scanner, nicht die Wortliste.** Ein Wort
 * unmittelbar vor `(` ist ein Funktionsaufruf. Ein Wort in Operatorstellung
 * kann Syntax sein und bleibt dann nackt; in **Operandenstellung** (am
 * Ausdrucksanfang, hinter `(`, `,`, einem Operatorzeichen oder einem Wort, auf
 * das ein Operand folgt) ist dasselbe Wort ein Bezeichner
 * ([MysqlReservedWords]). Und hinter `AS` bzw. hinter dem Komma eines
 * `CONVERT(` steht ein **Typname**, der bis zur schliessenden Klammer des
 * Aufrufs laeuft.
 *
 * **Der Typname ist mehrwortig.** Das MySQL-Handbuch („Cast Functions and
 * Operators", `CAST(expr AS type)`, `CONVERT(expr, type)`,
 * `CONVERT(expr USING charset)`) schreibt fuer `type` unter anderem
 * `SIGNED [INTEGER]`, `UNSIGNED [INTEGER]`, `CHAR[(N)] [charset_info]` mit
 * `charset_info: CHARACTER SET charset_name | ASCII | UNICODE`, dazu
 * `BINARY[(N)]`, `NCHAR[(N)]`, `DECIMAL[(M[,D])]`, `FLOAT[(p)]`, `DOUBLE`
 * (mit `PRECISION` als Zusatz), `REAL`, `DATE`, `DATETIME[(M)]`, `TIME[(M)]`,
 * `YEAR`, `JSON`, die Geometrietypen und den Zusatz `ARRAY`. Darin sind
 * `integer`, `precision`, `character` und `set` reserviert — nur das erste
 * Wort freizulassen, erzeugte `cast(total as signed \`integer\`)` und damit
 * `ERROR 1064` (gemessen auf 9.7.2 und 8.0.46; nackt nehmen beide Server
 * jede dieser Formen an). Der Typname endet deshalb erst mit der Klammer des
 * Aufrufs: `cast(x as char) = key` quotiert `key` wieder.
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
         * Ob an [position] ein **Operand** beginnt. Am Ausdrucksanfang tut er
         * das; sonst setzen die Klammer, das Komma, ein Operatorzeichen und
         * ein Wort aus `MysqlReservedWords.opensOperand` die Stellung.
         */
        private var operandPosition = true

        /** Wie viele Klammern an [position] offen sind. */
        private var depth = 0

        /**
         * Die Klammertiefe des `CAST`/`CONVERT`, dessen **Typname** gerade
         * laeuft, oder `null`. Im Typnamen wird nichts quotiert.
         */
        private var typeNameDepth: Int? = null

        /** Je offener Klammer: ist es die eines `CONVERT(`? */
        private val openCalls = ArrayDeque<Boolean>()

        /** Das Wort unmittelbar vor der naechsten `(`, sonst `null`. */
        private var callName: String? = null

        fun render(): String? {
            while (position < sql.length) {
                val char = sql[position]
                val ok = when {
                    char == '\'' -> afterOperand { literal() }
                    char == '"' -> afterOperand { doubleQuotedIdentifier() }
                    char == '`' -> afterOperand { verbatimUntilClosing('`') }
                    sql.startsWith("--", position) -> lineComment()
                    sql.startsWith("/*", position) -> blockComment()
                    isWordStart(char) || char.isDigit() -> wordOrNumber(char)
                    else -> punctuation(char)
                }
                if (!ok) return null
            }
            return out.toString()
        }

        /**
         * Ein Wort- oder Zahlenlauf. Quotiert wird nur ein Wort, das MySQL
         * reserviert und das hier kein Bezeichner sein kann: nicht vor `(`
         * (Funktionsaufruf), nicht im Typnamen eines `CAST`/`CONVERT` und
         * nicht in einer Stellung, in der es Syntax waere.
         */
        private fun wordOrNumber(first: Char): Boolean {
            var end = position
            while (end < sql.length && isWordPart(sql[end])) end++
            val word = sql.substring(position, end)
            position = end
            val call = nextCodeChar() == '('
            val quote = isWordStart(first) && !call && typeNameDepth == null &&
                MysqlReservedWords.mustQuoteAsIdentifier(word, operandPosition)
            out.append(if (quote) SqlIdentifiers.quoteIdentifier(word, DatabaseDialect.MYSQL) else word)
            callName = word.takeIf { call }
            operandPosition = !quote && MysqlReservedWords.opensOperand(word)
            if (!quote && typeNameDepth == null && word.equals("as", ignoreCase = true)) typeNameDepth = depth
            return true
        }

        /** Alles, was weder Wort noch Zahl, Literal oder Kommentar ist. */
        private fun punctuation(char: Char): Boolean {
            when {
                char == '(' -> openParenthesis()
                char == ')' -> closeParenthesis()
                char == ',' -> comma()
                // Ein Operatorzeichen: dahinter beginnt ein Operand, und ein
                // Typname traegt nur Woerter, Zahlen, Klammern und Kommas —
                // ein Operatorzeichen beendet ihn also auch ohne Klammer.
                !char.isWhitespace() -> {
                    operandPosition = true
                    typeNameDepth = null
                }
            }
            out.append(char)
            position++
            return true
        }

        private fun openParenthesis() {
            openCalls.addLast(callName.equals("convert", ignoreCase = true))
            callName = null
            depth++
            operandPosition = true
        }

        /** Mit der Klammer des Aufrufs endet der Typname. */
        private fun closeParenthesis() {
            openCalls.removeLastOrNull()
            depth--
            operandPosition = false
            typeNameDepth?.let { if (depth < it) typeNameDepth = null }
        }

        /** Hinter dem Komma eines `CONVERT(` steht der Typname. */
        private fun comma() {
            operandPosition = true
            if (typeNameDepth == null && openCalls.lastOrNull() == true) typeNameDepth = depth
        }

        /** Ein Token, das einen Operanden abschliesst. */
        private inline fun afterOperand(read: () -> Boolean): Boolean {
            operandPosition = false
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
