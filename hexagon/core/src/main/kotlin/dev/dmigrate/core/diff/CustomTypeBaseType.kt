package dev.dmigrate.core.diff

/**
 * Der Basistyp einer Domain, wie Datei und Reader ihn verschieden schreiben (Issue #52).
 *
 * Ein Reader liefert die Neutralform (`biginteger`, `text`, `decimal`, `float`), eine Datei
 * darf die Synonyme schreiben, die der Renderer annimmt (`bigint`, `int8`, `varchar(50)`,
 * `numeric`, `real`). Verglichen wird die Form ohne Laengen- und Genauigkeitsangabe; die
 * Genauigkeit eines `decimal` tragen `precision` und `scale` des Typs.
 */
internal object CustomTypeBaseType {

    private val CANONICAL = mapOf(
        "int" to "integer", "int4" to "integer", "integer" to "integer",
        "int8" to "biginteger", "bigint" to "biginteger", "biginteger" to "biginteger",
        "int2" to "smallint", "smallint" to "smallint",
        "text" to "text", "varchar" to "text", "character varying" to "text",
        "bpchar" to "text", "char" to "text", "character" to "text",
        "numeric" to "decimal", "decimal" to "decimal",
        "float" to "float", "float4" to "float", "float8" to "float", "real" to "float",
        "double" to "float", "double precision" to "float",
        "bool" to "boolean", "boolean" to "boolean",
    )

    fun canonical(baseType: String?): String? {
        val raw = baseType?.trim()?.lowercase()?.replace(Regex("\\s*\\(.*\\)\\s*$"), "")?.trim() ?: return null
        return CANONICAL[raw] ?: raw
    }
}
