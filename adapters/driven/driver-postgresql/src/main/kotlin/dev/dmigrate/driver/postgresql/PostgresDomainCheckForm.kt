package dev.dmigrate.driver.postgresql

/**
 * Der `CHECK` einer Domain in der Form, in der ein Autor ihn schreibt.
 *
 * `pg_get_constraintdef` liefert die vom Server umgeschriebene Form: eine Klammer-
 * ebene mehr (`CHECK ((VALUE > 0))`) und Literale mit Cast
 * (`(VALUE >= (0)::numeric)`, `(VALUE = 'a'::text)`). Der Reader bringt sie auf
 * die Autorenform zurueck, damit der Vergleich nicht an der Schreibweise haengt
 * (Issue #52). Das ist die Normalform des **Readers**, kein Falten im Vergleich
 * (ADR 0056). Was der Server darueber hinaus umschreibt (`BETWEEN`, `IN` →
 * `= ANY (ARRAY[…])`, ein Cast an `VALUE`), bleibt Katalogform.
 */
internal object PostgresDomainCheckForm {

    private val NUMERIC_LITERAL_CAST = Regex(
        """\(\s*(-?\d+(?:\.\d+)?)\s*\)::(?:numeric|double precision|real|smallint|integer|bigint)\b""",
    )

    /** Laengere Namen zuerst: `character varying` vor `character`. */
    private val TEXT_CASTS = listOf("character varying", "character", "varchar", "bpchar", "text")

    fun predicate(raw: String): String {
        var s = stripLiteralCasts(raw).trim()
        while (s.startsWith("(") && outerParenSpansWhole(s)) {
            s = s.substring(1, s.length - 1).trim()
        }
        return s
    }

    /** Entfernt den Cast an Zahl- und Textliteralen; Text in Anfuehrungszeichen bleibt unberuehrt. */
    private fun stripLiteralCasts(s: String): String {
        val out = StringBuilder(s.length)
        val outside = StringBuilder()
        var i = 0
        fun flush() {
            out.append(NUMERIC_LITERAL_CAST.replace(outside.toString(), "$1"))
            outside.setLength(0)
        }
        while (i < s.length) {
            if (s[i] != '\'') {
                outside.append(s[i++])
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

    private fun isCastAt(s: String, i: Int, type: String): Boolean {
        if (!s.startsWith("::", i) || !s.regionMatches(i + 2, type, 0, type.length)) return false
        val after = s.getOrNull(i + 2 + type.length)
        return after == null || !(after.isLetterOrDigit() || after == '_')
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

    /** True, wenn die oeffnende Klammer an Index 0 erst am letzten Zeichen geschlossen wird. */
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
