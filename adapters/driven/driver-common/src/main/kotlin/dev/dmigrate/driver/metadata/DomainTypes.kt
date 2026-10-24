package dev.dmigrate.driver.metadata

import dev.dmigrate.core.model.FloatPrecision
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
    private val TYPE_WITH_PARAMS = Regex("""^([A-Za-z][A-Za-z0-9_ ]*?)\s*\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\)$""")

    /**
     * Neutrale Typnamen (wie sie der Reverse in `base_type` ablegt) und die
     * gaengigen PostgreSQL-Katalognamen, die der PG-Reverse roh durchreicht.
     * `null`, wenn es kein neutrales Gegenstueck gibt (Aufrufer: E053, kein
     * Roh-Durchreichen).
     */
    fun resolveBaseType(baseType: String, precision: Int?, scale: Int?): NeutralType? {
        // Hand-geschriebene Formen `VARCHAR(20)` / `DECIMAL(10,2)`: Klammerwerte
        // zaehlen, wenn keine expliziten precision/scale am Typ stehen.
        val match = TYPE_WITH_PARAMS.matchEntire(baseType.trim())
        val name = match?.groupValues?.get(1) ?: baseType
        val parsedPrecision = match?.groupValues?.get(2)?.toIntOrNull()
        val parsedScale = match?.groupValues?.get(3)?.takeIf { it.isNotEmpty() }?.toIntOrNull()
        return resolveBaseTypeName(name, precision ?: parsedPrecision, scale ?: parsedScale)
    }

    private fun resolveBaseTypeName(baseType: String, precision: Int?, scale: Int?): NeutralType? =
        when (baseType.lowercase().trim()) {
            "integer", "int", "int4" -> NeutralType.Integer
            "biginteger", "bigint", "int8" -> NeutralType.BigInteger
            "smallint", "int2" -> NeutralType.SmallInt
            "boolean", "bool" -> NeutralType.BooleanType
            "real", "float4" -> NeutralType.Float(FloatPrecision.SINGLE)
            // Neutrales `float` ist double (Schema-Default float_precision=double).
            "float", "double precision", "float8", "double" -> NeutralType.Float()
            "decimal", "numeric" -> if (precision != null) NeutralType.Decimal(precision, scale ?: 0) else null
            "text", "character varying", "varchar" ->
                if (precision != null) NeutralType.Text(precision) else NeutralType.Text()
            "char", "character" -> NeutralType.Char(precision ?: 1)
            "uuid" -> NeutralType.Uuid
            "json", "jsonb" -> NeutralType.Json
            "xml" -> NeutralType.Xml
            "binary", "bytea" -> NeutralType.Binary
            "date" -> NeutralType.Date
            "time", "time without time zone" -> NeutralType.Time
            "datetime", "timestamp", "timestamp without time zone" -> NeutralType.DateTime()
            "timestamptz", "timestamp with time zone" -> NeutralType.DateTime(timezone = true)
            else -> null
        }

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
