package dev.dmigrate.core.diff

import dev.dmigrate.core.diff.RawSqlSkeleton.Companion.CLOSE
import dev.dmigrate.core.diff.RawSqlSkeleton.Companion.LITERAL

/**
 * Die Dialekt-Schreibweise eines rohen **Ausdrucks** — CHECK/EXCLUDE und
 * Index-Praedikat —, wie `schema compare` sie gleichsetzt (ADR 0056; die
 * Menge steht in `spec/cli-spec.md`).
 *
 * Kanonisiert wird **Schreibweise, nicht Bedeutung**. Was ein Parser
 * braeuchte, bleibt Unterschied; was der Scanner nicht sicher abgrenzt,
 * wird gar nicht gefaltet ([RawSqlSkeleton.of]).
 */
internal object ExpressionSpelling {

    /** Ob [left] und [right] sich nur in der Schreibweise unterscheiden. */
    fun equal(left: String, right: String): Boolean {
        if (left == right) return true
        val canonicalLeft = canonical(left) ?: return false
        val canonicalRight = canonical(right) ?: return false
        return canonicalLeft == canonicalRight
    }

    /** Die kanonische Form — `null`, wenn sich die Faltung zurueckzieht. */
    private fun canonical(sql: String): String? {
        val skeleton = RawSqlSkeleton.of(sql) ?: return null
        val folded = skeleton.text
            // PostgreSQL gibt `LIKE` als seinen internen Operator zurueck.
            .replace("~~", "like")
            .replace(TEXT_LITERAL_CAST, "$1")
            .replace(INTEGER_LITERAL_CAST, "$1")
            .replace(REDUNDANT_PARENS, "$1")
            .replace(WHITESPACE, " ")
            .replace(OPERATOR_GAP, "$1")
            .trim()
            .let(::stripOuterParens)
        return skeleton.restore(folded)
    }

    /**
     * Ein Cast, der den Wert **nicht aendern kann** — ohne Typwissen nur an
     * einem Literal erkennbar: ein String-Literal auf einen unbegrenzten
     * Texttyp. PostgreSQL haengt genau diesen Cast an (`'%@%'::text`).
     *
     * Kein Typmodifikator und kein Array: `'abc'::varchar(2)` kuerzt,
     * `'{a}'::text[]` ist ein anderer Wert. Ein Cast an einem Bezeichner
     * bleibt stehen — ob `price::integer` rundet, weiss nur der Typ der
     * Spalte.
     */
    private val TEXT_LITERAL_CAST = Regex(
        "($LITERAL\\d+$CLOSE)\\s*::\\s*(?i:text|varchar|character\\s+varying)\\b(?!\\s*[(\\[])",
    )

    /**
     * Ein ganzzahliges Literal — auch in Klammern, wie PostgreSQL es schreibt
     * (`(0)::numeric`) — auf einen unbegrenzten exakten Zahlentyp. Eine ganze
     * Zahl aendert dort weder Wert noch Gueltigkeit; `2.5::integer` rundet und
     * bleibt deshalb stehen, ebenso ein Cast mit Modifikator.
     */
    private val INTEGER_LITERAL_CAST = Regex(
        "(?<![A-Za-z0-9_.\\]$CLOSE])(\\(\\s*\\d+\\s*\\)|\\d+)(?![A-Za-z0-9_.])" +
            "\\s*::\\s*(?i:numeric|decimal)\\b(?!\\s*[(\\[])",
    )

    /**
     * Klammern, die **nur** ein Zahl-Literal oder einen Bezeichner
     * umschliessen — nicht die eines Funktionsaufrufs: `f(x)` ist kein `fx`.
     */
    private val REDUNDANT_PARENS = Regex("(?<![A-Za-z0-9_$\\]$CLOSE])\\(\\s*([A-Za-z0-9_.$]+)\\s*\\)")

    private val WHITESPACE = Regex("\\s+")

    /** Leerraum um Vergleichs- und Rechenoperatoren. */
    private val OPERATOR_GAP = Regex("\\s*([=<>!]+|\\*|\\+|-)\\s*")

    /**
     * Entfernt Klammern, die den **ganzen** Ausdruck umschliessen — und nur
     * solche. `(a > 0)` wird zu `a>0`; `(a + b) * c` bleibt. Eine naive
     * `removePrefix("(")` haette dort gegliedert und den Ausdruck verfaelscht.
     */
    private fun stripOuterParens(expression: String): String {
        var current = expression
        while (current.startsWith("(") && current.endsWith(")") && closesAtEnd(current)) {
            current = current.substring(1, current.length - 1).trim()
        }
        return current
    }

    /** `true`, wenn die Klammer an Position 0 erst am letzten Zeichen schliesst. */
    private fun closesAtEnd(expression: String): Boolean {
        var depth = 0
        for (index in expression.indices) {
            when (expression[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0 && index != expression.lastIndex) return false
                }
            }
        }
        return depth == 0
    }
}
