package dev.dmigrate.core.model

/**
 * Der Basistyp einer Domain als neutraler Typ und die Aufloesung einer Domain-Spalte darueber.
 *
 * Eine Spalte vom Typ einer Domain steht als `Enum(refType = <Domain>)`. Wo es auf den Typ der Spalte ankommt
 * (Default, Fremdschluessel, Erzeugungsart, Seed, Import), zaehlt der Basistyp der Domain; [effectiveType] loest
 * genau **eine** Stufe auf. Eine Domain ueber einer Domain, ein unbekannter Verweis und ein Basistyp ohne neutrales
 * Gegenstueck bleiben `Enum(refType)` -- der Rueckfall der Spec. Eine Enum (`kind: enum`) bleibt, was sie ist.
 */
object DomainBaseTypes {

    private val TYPE_WITH_PARAMS = Regex("""^([A-Za-z][A-Za-z0-9_ ]*?)\s*\(\s*(\d+)\s*(?:,\s*(\d+)\s*)?\)$""")

    /** Elementtypen eines Array-Basistyps, die das neutrale Modell kennt. */
    private val ARRAY_ELEMENTS = setOf("integer", "biginteger", "text", "boolean", "uuid", "float", "decimal", "json")

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

    /**
     * Der neutrale Basistyp der Domain, oder `null`, wo es keinen gibt (Domain ueber Domain, `bit(n)`, `inet`,
     * `numeric` ohne `precision`, Array ueber `varchar(n)`, ...). Der Typname geht ueber [CustomTypeBaseType]: Datei und
     * Reader schreiben ihn verschieden (`real` und `float`, `int4[]` und `INTEGER[]`), und zwei Domains, die der
     * Vergleich gleich nennt, haben denselben Basistyp.
     */
    fun baseTypeOf(domain: CustomTypeDefinition): NeutralType? {
        val base = CustomTypeBaseType.canonical(domain.baseType ?: "text") ?: return null
        if (base.endsWith("[]")) {
            return base.removeSuffix("[]").takeIf { it in ARRAY_ELEMENTS }?.let { NeutralType.Array(it) }
        }
        return resolveBaseType(base, CustomTypeBaseType.precision(domain), CustomTypeBaseType.scale(domain))
    }

    /** Der Typ der Spalte fuer Regeln, die den Typ brauchen: die Domain-Spalte als ihr Basistyp, sonst [type]. */
    fun effectiveType(type: NeutralType, customTypes: Map<String, CustomTypeDefinition>): NeutralType {
        val domain = (type as? NeutralType.Enum)?.refType?.let { customTypes[it] }
            ?.takeIf { it.kind == CustomTypeKind.DOMAIN } ?: return type
        return baseTypeOf(domain) ?: type
    }
}
