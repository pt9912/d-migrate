package dev.dmigrate.core.diff

import dev.dmigrate.core.diff.RawSqlSkeleton.Companion.CLOSE

/**
 * Die Dialekt-Schreibweise eines rohen **Ausdrucks** — CHECK/EXCLUDE und
 * Index-Praedikat —, wie `schema compare` sie gleichsetzt (ADR 0056; die
 * Menge steht in `spec/cli-spec.md`).
 *
 * Kanonisiert wird **Schreibweise, nicht Bedeutung**. Was ein Parser
 * braeuchte, bleibt Unterschied; was der Scanner nicht sicher abgrenzt,
 * wird gar nicht gefaltet ([RawSqlSkeleton.of]). Ein Cast faellt nur mit den
 * Spaltentypen der Tabelle **dieser** Seite ([ColumnCasts]) — ohne sie
 * bleibt jeder Cast stehen.
 */
internal object ExpressionSpelling {

    /** Ob [left] und [right] sich nur in der Schreibweise unterscheiden. */
    fun equal(
        left: String,
        right: String,
        leftColumns: ColumnTypes = ColumnTypes.NONE,
        rightColumns: ColumnTypes = ColumnTypes.NONE,
    ): Boolean {
        if (left == right) return true
        val canonicalLeft = canonical(left, leftColumns) ?: return false
        val canonicalRight = canonical(right, rightColumns) ?: return false
        return canonicalLeft == canonicalRight
    }

    /** Die kanonische Form — `null`, wenn sich die Faltung zurueckzieht. */
    private fun canonical(sql: String, columns: ColumnTypes): String? {
        val skeleton = RawSqlSkeleton.of(sql) ?: return null
        val folded = ColumnCasts.fold(skeleton.text, columns)
            // PostgreSQL gibt `LIKE` als seinen internen Operator zurueck —
            // mit Leerraum ersetzt, sonst hiesse `x~~y` danach `xlikey`.
            .replace(LIKE_OPERATOR, " like ")
            .replace(SqlLexis.WHITESPACE, " ")
            .let(::foldRedundantParens)
            .let(OperatorGap.EXPRESSION::fold)
            .let(OperandParens::stripEnclosing)
            .let(OperandParens::fold)
        return skeleton.restore(folded)
    }

    /** `~~` allein — nicht `!~~` (NOT LIKE), `~~*` (ILIKE) oder eine laengere Folge. */
    private val LIKE_OPERATOR = Regex("(?<![~!])~~(?![~*])")

    /**
     * Klammern, die **nur** ein Zahl-Literal oder einen Namen umschliessen.
     *
     * Stehen bleiben die Klammern eines Funktionsaufrufs — auch mit Leerraum
     * davor (`f (x)` ist in PostgreSQL ein Aufruf) —, die hinter `]`, `)` oder
     * einem Platzhalter direkt anschliessen, und die vor einem `.`:
     * `(addr).city` ist das Feld eines zusammengesetzten Werts, `addr.city`
     * die Spalte einer Tabelle. Hinter `AND`, `OR`, `NOT` und aehnlichen
     * Woertern umschliesst die Klammer einen Operanden und faellt.
     */
    private fun foldRedundantParens(text: String): String =
        PAREN_OPERAND.replace(text) { match ->
            if (syntaxBefore(text, match.range.first) || fieldAccessAfter(text, match.range.last + 1)) {
                match.value
            } else {
                match.groupValues[1]
            }
        }

    private val PAREN_OPERAND = Regex("\\( ?([${SqlLexis.NAME_CHARS}.]+) ?\\)")

    private fun syntaxBefore(text: String, open: Int): Boolean {
        if (open == 0) return false
        val direct = text[open - 1]
        if (direct == ']' || direct == ')' || direct == CLOSE) return true
        val end = if (direct == ' ') open - 1 else open
        if (end == 0 || !SqlLexis.isNameChar(text[end - 1])) return false
        val word = SqlLexis.wordBefore(text, end)
        return !SqlKeywords.precedesOperand(word)
    }

    private fun fieldAccessAfter(text: String, close: Int): Boolean {
        val next = if (text.getOrNull(close) == ' ') close + 1 else close
        return text.getOrNull(next) == '.'
    }
}

/**
 * Leerraum um bestimmte Operatoren und Satzzeichen ([gaps]).
 *
 * Im Ausdruck sind das Vergleichs- und Rechenoperatoren und das Komma —
 * ausserhalb eines Literals trennt ein Komma immer eine Liste, der Leerraum
 * daneben ist Schreibweise. Im Sichten-Rumpf Komma, `=` und Klammern.
 *
 * **Nicht** zwischen zwei Operatorzeichen, wo das Zusammenziehen die Lesung
 * aendert: `a < @ b` ist `a < abs(b)`, `a <@ b` ein anderer Operator. Nur wo
 * PostgreSQL die zusammengezogene Folge wieder genauso zerlegt — hinten nur
 * `+`/`-`, und keines der Zeichen, die einen Operator verlaengern —, faellt
 * auch dieser Leerraum (`a < -1` gleich `a<-1`).
 *
 * Erwartet Text mit hoechstens einem Leerzeichen am Stueck.
 */
internal class OperatorGap private constructor(private val gaps: String) {

    fun fold(text: String): String {
        val out = StringBuilder(text.length)
        for ((index, char) in text.withIndex()) {
            if (char == ' ' && removable(text, index)) continue
            out.append(char)
        }
        return out.toString().trim()
    }

    private fun removable(text: String, space: Int): Boolean {
        val before = text.getOrNull(space - 1) ?: return true
        val after = text.getOrNull(space + 1) ?: return true
        if (before !in gaps && after !in gaps) return false
        if (!isOperatorChar(before) || !isOperatorChar(after)) return true
        return mergesSafely(run(text, space - 1, -1), run(text, space + 1, 1))
    }

    private fun mergesSafely(left: String, right: String): Boolean {
        val merged = left + right
        if (merged.contains("--") || merged.contains("/*")) return false
        return right.all { it == '+' || it == '-' } && merged.none { it in EXTENDING }
    }

    private fun run(text: String, from: Int, step: Int): String {
        val chars = StringBuilder()
        var index = from
        while (index in text.indices && isOperatorChar(text[index])) {
            chars.append(text[index])
            index += step
        }
        return if (step < 0) chars.reverse().toString() else chars.toString()
    }

    private fun isOperatorChar(char: Char): Boolean = SqlTokens.OPERATOR_CHARS.indexOf(char) >= 0

    companion object {
        /** CHECK und Index-Praedikat. */
        val EXPRESSION = OperatorGap("=<>!*+-/%,")

        /** Der Sichten-Rumpf — enger, siehe [QuerySpelling]. */
        val QUERY = OperatorGap(",=()")

        /** Zeichen, mit denen PostgreSQL ein `+`/`-` am Ende eines Operators stehen laesst. */
        private const val EXTENDING = "~!@#^&|`?%"
    }
}
