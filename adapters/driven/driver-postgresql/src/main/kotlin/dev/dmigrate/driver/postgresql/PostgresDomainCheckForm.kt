package dev.dmigrate.driver.postgresql

/**
 * Der `CHECK` einer Domain in der Form, in der ein Autor ihn schreibt.
 *
 * `pg_get_constraintdef` liefert die vom Server umgeschriebene Form: eine Klammerebene mehr
 * (`CHECK ((VALUE > 0))`) und Literale mit Cast (`(VALUE >= (0)::numeric)`,
 * `(VALUE = 'a'::text)`). Der Reader bringt sie auf die Autorenform zurück, damit der Vergleich
 * nicht an der Schreibweise hängt (Issue #52). Das ist die Normalform des **Readers**, kein
 * Falten im Vergleich (ADR 0056).
 *
 * Zurückhaltend gebaut: ein Cast fällt nur an einem einfachen Zahl- oder Textliteral, und nur
 * wenn keine Rechenoperation (`/ % ^`) im Ausdruck steht — dort bestimmt der Cast den Typ der
 * Rechnung (`(10)::numeric / (4)::numeric`), und das Streichen änderte die Bedeutung. Auch ein
 * Cast mit Typmodifikator (`'x'::character varying(10)`) oder Array-Cast (`::text[]`) bleibt.
 * Negative Zahlen bleiben unverändert (der Server setzt dort Klammern oder Anführungszeichen,
 * ungemessen). Was der Server darüber hinaus umschreibt (`BETWEEN`, `IN (…)` →
 * `= ANY (ARRAY[…])`, ein Cast an `VALUE`), bleibt Katalogform. Literale sind Standardliterale
 * (`standard_conforming_strings = on`, `''` als Escape); `pg_get_constraintdef` erzeugt weder
 * Dollar-Quotes noch Backslash-Escapes.
 */
internal object PostgresDomainCheckForm {

    private val NUMERIC_LITERAL_CAST = Regex(
        """\(\s*(\d+(?:\.\d+)?)\s*\)::(?:numeric|double precision|real|smallint|integer|bigint)\b(?!\s*[(\[])""",
    )

    /** Längere Namen zuerst: `character varying` vor `character`. */
    private val TEXT_CASTS = listOf("character varying", "character", "varchar", "bpchar", "text")

    private val ARITHMETIC = setOf('/', '%', '^')

    private const val NOT_VALID = " NOT VALID"

    /**
     * Ob `pg_get_constraintdef` den Constraint als `NOT VALID` ausweist. Das Suffix steht am Ende der
     * Definition; ein Literal endet auf ein Hochkomma und kann es nicht vortaeuschen.
     */
    fun isNotValid(raw: String): Boolean = raw.trimEnd().endsWith(NOT_VALID, ignoreCase = true)

    /** Die Definition ohne das `NOT VALID`-Suffix; das Praedikat bleibt (der Constraint prueft Neues weiter). */
    fun withoutNotValid(raw: String): String {
        val trimmed = raw.trimEnd()
        return if (isNotValid(trimmed)) trimmed.dropLast(NOT_VALID.length) else trimmed
    }

    /**
     * Verbindet die Praedikate mehrerer CHECKs einer Domain: ein einzelnes bleibt unveraendert, ab zwei steht
     * jedes in Klammern und `AND` verbindet sie. Eine Domain verwirft nur bei `false`, `a AND b` ist also
     * aequivalent zu zwei Constraints.
     */
    fun conjunction(predicates: List<String>): String? = when (predicates.size) {
        0 -> null
        1 -> predicates.single()
        else -> predicates.joinToString(" AND ") { "($it)" }
    }

    fun predicate(raw: String): String {
        var s = stripLiteralCasts(raw).trim()
        while (s.startsWith("(") && outerParenSpansWhole(s)) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s
    }

    /** Entfernt den Cast an Zahl- und Textliteralen; Text in Anführungszeichen bleibt unberührt. */
    private fun stripLiteralCasts(s: String): String {
        if (hasArithmeticOutsideLiterals(s)) return s
        val out = StringBuilder(s.length)
        val outside = StringBuilder()
        fun flush() {
            out.append(NUMERIC_LITERAL_CAST.replace(outside.toString(), "$1"))
            outside.setLength(0)
        }
        var i = 0
        while (i < s.length) {
            if (s[i] != '\'') {
                outside.append(s[i])
                i++
                continue
            }
            flush()
            val end = literalEnd(s, i)
            out.append(s, i, end)
            i = end
            TEXT_CASTS.firstOrNull { isCastAt(s, i, it) }?.let { i += 2 + it.length }
        }
        flush()
        return out.toString()
    }

    private fun hasArithmeticOutsideLiterals(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '\'' -> i = literalEnd(s, i)
                s[i] in ARITHMETIC -> return true
                else -> i++
            }
        }
        return false
    }

    /** `::typ` bei [i], ganzes Wort und ohne Typmodifikator `(n)` oder Array-Klammer danach. */
    private fun isCastAt(s: String, i: Int, type: String): Boolean {
        if (!s.startsWith("::", i) || !s.regionMatches(i + 2, type, 0, type.length)) return false
        val end = i + 2 + type.length
        // `character` ist auch der Anfang von `character varying`.
        if (type == "character" && s.startsWith(" varying", end)) return false
        val after = s.getOrNull(end)
        return after == null || !(after.isLetterOrDigit() || after == '_' || after == '(' || after == '[')
    }

    private fun literalEnd(s: String, start: Int): Int {
        var j = start + 1
        while (j < s.length) {
            if (s[j] != '\'') {
                j++
            } else if (j + 1 < s.length && s[j + 1] == '\'') {
                j += 2
            } else {
                return j + 1
            }
        }
        return s.length
    }

    /** True, wenn die öffnende Klammer an Index 0 erst am letzten Zeichen geschlossen wird. */
    private fun outerParenSpansWhole(s: String): Boolean {
        var depth = 0
        var i = 0
        while (i < s.length) {
            when (s[i]) {
                '\'' -> {
                    i = literalEnd(s, i)
                    continue
                }
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i == s.length - 1
                }
            }
            i++
        }
        return false
    }
}
