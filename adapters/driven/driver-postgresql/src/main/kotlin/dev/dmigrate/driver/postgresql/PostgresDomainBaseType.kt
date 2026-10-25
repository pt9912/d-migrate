package dev.dmigrate.driver.postgresql

/**
 * Der Basistyp einer Domain aus `format_type(typbasetype, typtypmod)`, in der Neutralform, die
 * der Reverse in `base_type` ablegt (Issue #58).
 *
 * Eigene Abbildung statt [PostgresTypeMapping.mapParamType]: die kennt `udt_name`-Namen (`int4`),
 * keine Laenge und kein Array, und die Routinen-Reader haengen daran. Laenge und Genauigkeit
 * bleiben in der Klammer (`varchar(50)`, `char(5)`, `timestamp(3)`), ein Array traegt `[]`; die
 * Neutralform ist die von `CustomTypeBaseType`. `numeric(p,s)` wird ohne Array zu `decimal` mit
 * `precision`/`scale` in den Feldern der Domain (ADR 0061), mit Array zu `decimal(p,s)[]`.
 */
internal object PostgresDomainBaseType {

    /** Neutraler Basistyp samt `precision`/`scale`, falls der Typ `decimal` ist. */
    data class Mapped(val baseType: String, val precision: Int? = null, val scale: Int? = null)

    private val ARRAY_SUFFIX = Regex("""(\[\d*])+$""")
    private val PARAMETERS = Regex("""^(.*?)\((\d+)(?:,\s*(\d+))?\)(.*)$""")
    private val NUMERIC = Regex("""^numeric\((\d+),\s*(\d+)\)$""")

    private val NAMES = mapOf(
        "integer" to "integer", "bigint" to "biginteger", "smallint" to "smallint",
        "real" to "float", "double precision" to "float", "numeric" to "decimal",
        "character varying" to "varchar", "character" to "char",
        "boolean" to "boolean", "jsonb" to "json", "bytea" to "binary",
        "bit varying" to "varbit",
    )

    fun map(formatType: String): Mapped {
        val raw = formatType.trim()
        val array = ARRAY_SUFFIX.containsMatchIn(raw)
        val element = raw.replace(ARRAY_SUFFIX, "")
        if (!array) NUMERIC.matchEntire(element)?.let { m ->
            return Mapped("decimal", m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        return Mapped(neutral(element) + if (array) "[]" else "")
    }

    /** Ohne `[]`: `character varying(50)` -> `varchar(50)`, `timestamp(3) with time zone` -> `timestamptz(3)`. */
    private fun neutral(element: String): String {
        val match = PARAMETERS.matchEntire(element)
        val name = match?.groupValues?.get(1)?.trim() ?: element
        val parameters = match?.let {
            "(" + listOf(it.groupValues[2], it.groupValues[3]).filter(String::isNotEmpty).joinToString(",") + ")"
        }.orEmpty()
        val tail = match?.groupValues?.get(4)?.trim().orEmpty()
        val spelled = listOf(name, tail).filter(String::isNotEmpty).joinToString(" ")
        val timeName = when (spelled) {
            "timestamp without time zone", "timestamp" -> "timestamp"
            "timestamp with time zone" -> "timestamptz"
            "time without time zone", "time" -> "time"
            "time with time zone" -> "timetz"
            else -> null
        }
        // `varchar` ohne Laenge ist unbegrenzt, wie `text`; als `text` bleibt es in jedem Ziel ein gueltiger Typ.
        if (spelled == "character varying" && parameters.isEmpty()) return "text"
        return (timeName ?: NAMES[spelled] ?: spelled) + parameters
    }
}
