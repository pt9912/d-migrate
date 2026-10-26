package dev.dmigrate.core.seed

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Die auswertbare Form des `CHECK` einer Domain, soweit `data seed` sie versteht: eine Konjunktion aus
 *
 * * `VALUE <op> <Zahl>` (`>`, `>=`, `<`, `<=`, `=`, `<>`, `!=`), auch als `VALUE BETWEEN a AND b`;
 * * `VALUE <> '<Text>'`;
 * * `length(VALUE) <op> <n>` (auch `char_length`);
 * * `VALUE IN (...)` bzw. die Katalogform `VALUE = ANY (ARRAY[...])`;
 * * `VALUE IS NOT NULL` (ohne Wirkung).
 *
 * Alles andere (Regex, Funktionen, Verweise) versteht [parse] nicht und liefert `null`: dann erzeugt `data seed`
 * keinen Wert, der den `CHECK` raten muesste, sondern meldet es.
 */
internal class DomainCheck private constructor(private val terms: List<Term>) {

    private sealed interface Term {
        data class Compare(val op: String, val bound: BigDecimal) : Term
        data class TextNotEqual(val literal: String) : Term
        data class Length(val op: String, val bound: Int) : Term
        data class OneOf(val values: List<String>) : Term
    }

    /** Untere/obere Schranke; `inclusive` sagt, ob die Schranke selbst zaehlt. */
    data class Bound(val value: BigDecimal, val inclusive: Boolean)

    data class Bounds(val lower: Bound?, val upper: Bound?) {
        /** Der kleinste zulaessige Wert auf dem Raster der Skala [scale], oder `null` ohne untere Schranke. */
        fun lowestAt(scale: Int): BigDecimal? = lower?.let {
            val rounded = it.value.setScale(scale, RoundingMode.CEILING)
            if (!it.inclusive && rounded.compareTo(it.value) == 0) rounded + step(scale) else rounded
        }

        /** Der groesste zulaessige Wert auf dem Raster der Skala [scale], oder `null` ohne obere Schranke. */
        fun highestAt(scale: Int): BigDecimal? = upper?.let {
            val rounded = it.value.setScale(scale, RoundingMode.FLOOR)
            if (!it.inclusive && rounded.compareTo(it.value) == 0) rounded - step(scale) else rounded
        }

        private fun step(scale: Int): BigDecimal = BigDecimal.ONE.movePointLeft(scale)
    }

    fun bounds(): Bounds {
        var lower: Bound? = null
        var upper: Bound? = null
        for (term in terms.filterIsInstance<Term.Compare>()) {
            val bound = Bound(term.bound, term.op == ">=" || term.op == "<=" || term.op == "=")
            if (term.op in LOWER_OPS) lower = tighter(lower, bound, lowerSide = true)
            if (term.op in UPPER_OPS) upper = tighter(upper, bound, lowerSide = false)
        }
        return Bounds(lower, upper)
    }

    private fun tighter(current: Bound?, candidate: Bound, lowerSide: Boolean): Bound {
        if (current == null) return candidate
        val order = candidate.value.compareTo(current.value)
        val candidateWins = if (lowerSide) order > 0 else order < 0
        return when {
            candidateWins -> candidate
            order == 0 && !candidate.inclusive -> candidate
            else -> current
        }
    }

    /** Die Werte, aus denen ein `IN`-/`ANY`-Term waehlt (der Schnitt mehrerer), oder `null` ohne einen solchen Term. */
    fun allowedValues(): List<String>? =
        terms.filterIsInstance<Term.OneOf>().map { it.values }.reduceOrNull { a, b -> a.filter { it in b } }

    fun accepts(value: Any?): Boolean = value == null || terms.all { accepts(it, value) }

    private fun accepts(term: Term, value: Any): Boolean = when (term) {
        is Term.Compare -> toBigDecimal(value)?.let { compare(term.op, it.compareTo(term.bound)) } ?: false
        is Term.TextNotEqual -> value.toString() != term.literal
        is Term.Length -> compare(term.op, value.toString().length.compareTo(term.bound))
        is Term.OneOf -> term.values.any { sameValue(it, value) }
    }

    private fun sameValue(literal: String, value: Any): Boolean {
        val number = toBigDecimal(value)
        val literalNumber = literal.toBigDecimalOrNull()
        return if (number != null && literalNumber != null) number.compareTo(literalNumber) == 0 else literal == value.toString()
    }

    private fun toBigDecimal(value: Any): BigDecimal? = when (value) {
        is BigDecimal -> value
        is Long -> BigDecimal.valueOf(value)
        is Int -> BigDecimal.valueOf(value.toLong())
        is Double -> BigDecimal.valueOf(value)
        else -> null
    }

    private fun compare(op: String, order: Int): Boolean = when (op) {
        ">" -> order > 0
        ">=" -> order >= 0
        "<" -> order < 0
        "<=" -> order <= 0
        "=" -> order == 0
        else -> order != 0
    }

    companion object {
        private const val NUMBER = """-?\d+(?:\.\d+)?"""
        private const val OPS = """>=|<=|<>|!=|>|<|="""
        private val LOWER_OPS = setOf(">", ">=", "=")
        private val UPPER_OPS = setOf("<", "<=", "=")
        private val BETWEEN = Regex("""VALUE\s+BETWEEN\s+($NUMBER)\s+AND\s+($NUMBER)""", RegexOption.IGNORE_CASE)
        private val AND_SPLIT = Regex("""\s+AND\s+""", RegexOption.IGNORE_CASE)
        private val COMPARE = Regex("""^VALUE\s*($OPS)\s*($NUMBER)$""", RegexOption.IGNORE_CASE)
        private val TEXT_NOT_EQUAL = Regex("""^VALUE\s*(?:<>|!=)\s*'((?:[^']|'')*)'$""", RegexOption.IGNORE_CASE)
        private val LENGTH = Regex("""^(?:char_)?length\(\s*VALUE\s*\)\s*($OPS)\s*(\d+)$""", RegexOption.IGNORE_CASE)
        private val IN_LIST = Regex("""^VALUE\s+IN\s*\((.*)\)$""", RegexOption.IGNORE_CASE)
        private val ANY_ARRAY = Regex(
            """^VALUE\s*=\s*ANY\s*\(\s*\(?\s*ARRAY\s*\[(.*)]\s*\)?(?:::[\w\s\[\]]+)?\s*\)$""", RegexOption.IGNORE_CASE,
        )
        private val NOT_NULL = Regex("""^VALUE\s+IS\s+NOT\s+NULL$""", RegexOption.IGNORE_CASE)
        private val SINGLE_LITERAL = Regex("""^'(?:[^']|'')*'$""")
        private val CAST = Regex("""::[A-Za-z_][\w ]*(\[])?""")

        /** `null`, wenn der `CHECK` (oder einer seiner Terme) ausserhalb dessen liegt, was [DomainCheck] versteht. */
        fun parse(check: String): DomainCheck? {
            val withBetween = BETWEEN.replace(check.trim()) { "VALUE >= ${it.groupValues[1]} AND VALUE <= ${it.groupValues[2]}" }
            val terms = ArrayList<Term>()
            for (raw in splitTopLevel(withBetween)) {
                val text = stripParens(raw)
                if (NOT_NULL.matches(text)) continue
                terms += parseTerm(text) ?: return null
            }
            return DomainCheck(terms)
        }

        private fun parseTerm(text: String): Term? {
            COMPARE.matchEntire(text)?.let { return Term.Compare(it.groupValues[1], BigDecimal(it.groupValues[2])) }
            TEXT_NOT_EQUAL.matchEntire(text)?.let { return Term.TextNotEqual(it.groupValues[1].replace("''", "'")) }
            LENGTH.matchEntire(text)?.let { return Term.Length(it.groupValues[1], it.groupValues[2].toInt()) }
            (IN_LIST.matchEntire(text) ?: ANY_ARRAY.matchEntire(text))?.let { m -> return listTerm(m.groupValues[1]) }
            return null
        }

        /** Jedes Element muss **ein** Literal sein; ein Rest (etwa `'a') OR VALUE IN ('b'`) ist kein Wertevorrat. */
        private fun listTerm(body: String): Term? {
            val values = splitList(CAST.replace(body, "")).map { item ->
                val s = item.trim()
                when {
                    SINGLE_LITERAL.matches(s) -> s.substring(1, s.length - 1).replace("''", "'")
                    s.toBigDecimalOrNull() != null -> s
                    else -> return null
                }
            }
            return if (values.isEmpty()) null else Term.OneOf(values)
        }

        private fun splitList(body: String): List<String> {
            val out = ArrayList<String>()
            val current = StringBuilder()
            var inQuote = false
            for (ch in body) {
                if (ch == '\'') inQuote = !inQuote
                if (ch == ',' && !inQuote) { out += current.toString(); current.clear() } else current.append(ch)
            }
            if (current.isNotBlank()) out += current.toString()
            return out
        }

        /** Trennt an `AND` ausserhalb von Klammern und Literalen. */
        private fun splitTopLevel(text: String): List<String> {
            val parts = ArrayList<String>()
            var depth = 0
            var inQuote = false
            var start = 0
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                if (ch == '\'') inQuote = !inQuote
                if (!inQuote) {
                    if (ch == '(' || ch == '[') depth++
                    if (ch == ')' || ch == ']') depth--
                    if (depth == 0) {
                        val m = AND_SPLIT.matchAt(text, i)
                        if (m != null) { parts += text.substring(start, i); i = m.range.last + 1; start = i; continue }
                    }
                }
                i++
            }
            parts += text.substring(start)
            return parts
        }

        private fun stripParens(raw: String): String {
            var s = raw.trim()
            while (s.startsWith("(") && s.endsWith(")") && balanced(s.substring(1, s.length - 1))) s = s.substring(1, s.length - 1).trim()
            return s
        }

        private fun balanced(inner: String): Boolean {
            var depth = 0
            var inQuote = false
            for (ch in inner) {
                if (ch == '\'') inQuote = !inQuote
                if (inQuote) continue
                if (ch == '(') depth++
                if (ch == ')') { depth--; if (depth < 0) return false }
            }
            return depth == 0
        }
    }
}
