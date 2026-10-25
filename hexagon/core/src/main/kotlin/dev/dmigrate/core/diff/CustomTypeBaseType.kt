package dev.dmigrate.core.diff

import dev.dmigrate.core.model.CustomTypeDefinition

/**
 * Der Basistyp einer Domain, wie Datei und Reader ihn verschieden schreiben (Issue #52, #58).
 *
 * Ein Reader liefert die Neutralform (`biginteger`, `text`, `decimal`, `float`, `json`,
 * `binary`, `varchar(50)`, `char(5)`, `integer[]`), eine Datei darf die Synonyme schreiben, die der
 * Renderer annimmt (`bigint`, `int8`, `character varying(50)`, `numeric(10,2)`, `real`, `jsonb`,
 * `bytea`). Der Typname wird auf die Neutralform gebracht.
 *
 * * `precision`/`scale` zaehlen nur bei `decimal`; den Wert nennt entweder das Feld oder die
 *   Klammer des Typs ([precision], [scale]). Der Katalog fuellt sie auch fuer `integer` (32/0),
 *   eine alte Reverse-Datei traegt sie dann noch.
 * * **Typen mit Laenge oder Genauigkeit** (`varchar`, `char`, `timestamp`, `timestamptz`, `time`,
 *   `bit`, `varbit`) tragen die Klammer in der Neutralform: `varchar(50)` ist ein anderer Typ als
 *   `varchar(254)`. Ohne Klammer gilt der Standard des Typs: `varchar` ist unbegrenzt und damit
 *   `text`, `char` ist `char(1)`, `timestamp`/`time` sind `(6)`.
 * * **Array:** ein Suffix `[]` (auch `[][]`, `[3]`) bleibt als ein `[]` am Typnamen. Der Katalog
 *   unterscheidet Dimensionen nicht.
 * * Die Klammer jedes anderen Typs (`integer(11)`) wird nur verglichen, wenn **beide** Seiten sie
 *   tragen ([sameParameters]); fehlt sie einer Seite, ist sie unbekannt, nicht verschieden.
 */
internal object CustomTypeBaseType {

    private val PARAMETERS = Regex("""\s*\(([^)]*)\)\s*$""")
    private val ARRAY_SUFFIX = Regex("""(\s*\[\s*\d*\s*])+\s*$""")
    private val TIME_ZONE_FORM = Regex("""^(timestamp|time)\s*(\([^)]*\))?\s+(with|without)\s+time\s+zone$""")

    /** Typname -> Standard der Klammer, wenn der Typ keine nennt. */
    private val DEFAULT_PARAMETER = mapOf(
        "char" to 1, "timestamp" to 6, "timestamptz" to 6, "time" to 6, "bit" to 1,
    )
    private val PARAMETERISED = DEFAULT_PARAMETER.keys + setOf("varchar", "varbit")

    /** Synonym (klein geschrieben, ohne Klammer) auf die Neutralform des Readers. */
    private val SYNONYMS = mapOf(
        "int" to "integer", "int4" to "integer", "integer" to "integer",
        "int8" to "biginteger", "bigint" to "biginteger", "biginteger" to "biginteger",
        "int2" to "smallint", "smallint" to "smallint",
        "text" to "text", "varchar" to "varchar", "character varying" to "varchar",
        "char" to "char", "character" to "char",
        "numeric" to "decimal", "decimal" to "decimal",
        "float" to "float", "float4" to "float", "float8" to "float", "real" to "float",
        "double" to "float", "double precision" to "float",
        "bool" to "boolean", "boolean" to "boolean",
        "json" to "json", "jsonb" to "json",
        "bytea" to "binary", "binary" to "binary",
        "timestamp" to "timestamp", "timestamp without time zone" to "timestamp",
        "timestamptz" to "timestamptz", "timestamp with time zone" to "timestamptz",
        "time" to "time", "time without time zone" to "time",
        "bit" to "bit", "bit varying" to "varbit", "varbit" to "varbit",
    )

    private data class Parts(val name: String, val parameters: List<Int>, val array: Boolean)

    private fun split(baseType: String?): Parts? {
        val raw = baseType?.trim()?.lowercase() ?: return null
        val array = ARRAY_SUFFIX.containsMatchIn(raw)
        val element = raw.replace(ARRAY_SUFFIX, "").trim()
        val spelled = TIME_ZONE_FORM.matchEntire(element)?.let { m ->
            val zone = if (m.groupValues[3] == "with") "tz" else ""
            m.groupValues[1] + zone + m.groupValues[2]
        } ?: element
        val parameters = PARAMETERS.find(spelled)?.groupValues?.get(1)
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
        val name = spelled.replace(PARAMETERS, "").trim()
        // `bpchar(5)` ist `char(5)`; ohne Klammer ist `bpchar` unbegrenzt und ein eigener Typ.
        val known = if (name == "bpchar" && parameters.isNotEmpty()) "char" else SYNONYMS[name] ?: name
        return Parts(known, parameters, array)
    }

    /**
     * Der Typ in Neutralform; `null` ohne Basistyp. Bei Typen mit Laenge steht der Wert in der
     * Klammer (`varchar(50)`, `char(1)`), ein Array traegt `[]`.
     */
    fun canonical(baseType: String?): String? {
        val parts = split(baseType) ?: return null
        val core = when {
            parts.name == "varchar" && parts.parameters.isEmpty() -> "text"
            parts.name in PARAMETERISED -> {
                val value = parts.parameters.firstOrNull() ?: DEFAULT_PARAMETER[parts.name]
                if (value == null) parts.name else "${parts.name}($value)"
            }
            else -> parts.name
        }
        return if (parts.array) "$core[]" else core
    }

    /** Die Zahlen in der Klammer (`numeric(10,2)` -> `[10, 2]`), leer ohne Klammer. */
    fun parameters(baseType: String?): List<Int> = split(baseType)?.parameters.orEmpty()

    fun precision(type: CustomTypeDefinition): Int? =
        if (canonical(type.baseType) == "decimal") type.precision ?: parameters(type.baseType).getOrNull(0) else null

    fun scale(type: CustomTypeDefinition): Int? =
        if (canonical(type.baseType) == "decimal") type.scale ?: parameters(type.baseType).getOrNull(1) else null

    /**
     * Beide Seiten tragen eine Klammer und sie ist verschieden? Dann nicht gleich. Gilt nicht fuer
     * Typen mit Laenge: deren Klammer steht schon in [canonical].
     */
    fun sameParameters(left: String?, right: String?): Boolean {
        val l = parameters(left)
        val r = parameters(right)
        return l.isEmpty() || r.isEmpty() || canonical(left) == "decimal" ||
            split(left)?.name in PARAMETERISED || l == r
    }
}
