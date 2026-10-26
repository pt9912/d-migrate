package dev.dmigrate.driver.metadata

import dev.dmigrate.core.model.DomainBaseTypes
import dev.dmigrate.core.model.NeutralType

/**
 * Was ein Dialekt ohne eigenen Domain-Typ von einer Domain braucht: den
 * Basistyp als neutralen Typ und den `CHECK`-Ausdruck mit der Spalte statt des
 * PostgreSQL-Platzhalterworts `VALUE`.
 *
 * Eine Stelle statt einer je Dialekt: `schema generate`, `schema migrate` und
 * der Typ-Kanonisierer muessen dieselbe Spalte schreiben und lesen.
 */
object DomainTypes {

    private val VALUE_TOKEN = Regex("""(?i)\bVALUE\b""")
    /** Siehe [DomainBaseTypes.resolveBaseType]. */
    fun resolveBaseType(baseType: String, precision: Int?, scale: Int?): NeutralType? =
        DomainBaseTypes.resolveBaseType(baseType, precision, scale)

    /** Ersetzt das Wort `VALUE` ausserhalb von `'…'`-Literalen (`''` = Escape). */
    fun substituteValueToken(check: String, replacement: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < check.length) {
            if (check[index] == '\'') {
                val end = endOfStringLiteral(check, index)
                out.append(check, index, end)
                index = end
            } else {
                val next = check.indexOf('\'', index).let { if (it < 0) check.length else it }
                out.append(VALUE_TOKEN.replace(check.substring(index, next), replacement))
                index = next
            }
        }
        return out.toString()
    }

    private fun endOfStringLiteral(value: String, openQuote: Int): Int {
        var index = openQuote + 1
        while (index < value.length) {
            index += when {
                value[index] != '\'' -> 1
                index + 1 < value.length && value[index + 1] == '\'' -> 2
                else -> return index + 1
            }
        }
        return index
    }
}
