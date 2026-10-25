package dev.dmigrate.core.diff

import dev.dmigrate.core.model.CustomTypeDefinition

/**
 * Der Basistyp einer Domain, wie Datei und Reader ihn verschieden schreiben (Issue #52).
 *
 * Ein Reader liefert die Neutralform (`biginteger`, `text`, `decimal`, `float`, `json`,
 * `binary`), eine Datei darf die Synonyme schreiben, die der Renderer annimmt (`bigint`,
 * `int8`, `varchar(50)`, `numeric(10,2)`, `real`, `jsonb`, `bytea`). Der Typname wird auf die
 * Neutralform gebracht; eine Klammerangabe (`(10,2)`, `(50)`) bleibt als Parameter erhalten.
 *
 * * `precision`/`scale` zaehlen nur bei `decimal`; den Wert nennt entweder das Feld oder die
 *   Klammer des Typs ([precision], [scale]). Der Katalog fuellt sie auch fuer `integer` (32/0),
 *   eine alte Reverse-Datei traegt sie dann noch.
 * * Die Klammer eines Typs mit Laenge (`varchar(50)`) liest der Reader nicht. Verglichen wird sie
 *   nur, wenn **beide** Seiten sie tragen ([sameParameters]); fehlt sie einer Seite, ist sie
 *   unbekannt, nicht verschieden.
 */
internal object CustomTypeBaseType {

    private val PARAMETERS = Regex("""\s*\(([^)]*)\)\s*$""")

    /** Synonym (klein geschrieben, ohne Klammer) auf die Neutralform des Readers. */
    private val SYNONYMS = mapOf(
        "int" to "integer", "int4" to "integer", "integer" to "integer",
        "int8" to "biginteger", "bigint" to "biginteger", "biginteger" to "biginteger",
        "int2" to "smallint", "smallint" to "smallint",
        "text" to "text", "varchar" to "text", "character varying" to "text",
        "bpchar" to "text", "char" to "text", "character" to "text",
        "numeric" to "decimal", "decimal" to "decimal",
        "float" to "float", "float4" to "float", "float8" to "float", "real" to "float",
        "double" to "float", "double precision" to "float",
        "bool" to "boolean", "boolean" to "boolean",
        "json" to "json", "jsonb" to "json",
        "bytea" to "binary", "binary" to "binary",
    )

    /** Der Typname in Neutralform; `null` ohne Basistyp. */
    fun canonical(baseType: String?): String? {
        val raw = baseType?.trim()?.lowercase() ?: return null
        val name = raw.replace(PARAMETERS, "").trim()
        return SYNONYMS[name] ?: name
    }

    /** Die Zahlen in der Klammer (`numeric(10,2)` -> `[10, 2]`), leer ohne Klammer. */
    fun parameters(baseType: String?): List<Int> =
        PARAMETERS.find(baseType.orEmpty())?.groupValues?.get(1)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

    fun precision(type: CustomTypeDefinition): Int? =
        if (canonical(type.baseType) == "decimal") type.precision ?: parameters(type.baseType).getOrNull(0) else null

    fun scale(type: CustomTypeDefinition): Int? =
        if (canonical(type.baseType) == "decimal") type.scale ?: parameters(type.baseType).getOrNull(1) else null

    /** Beide Seiten tragen eine Laengenangabe und sie ist verschieden? Dann nicht gleich. */
    fun sameParameters(left: String?, right: String?): Boolean {
        val l = parameters(left)
        val r = parameters(right)
        return l.isEmpty() || r.isEmpty() || canonical(left) == "decimal" || l == r
    }
}
