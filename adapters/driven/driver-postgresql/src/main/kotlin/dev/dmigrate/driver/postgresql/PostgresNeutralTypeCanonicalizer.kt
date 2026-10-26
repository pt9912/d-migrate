package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.diff.CustomTypeBaseType
import dev.dmigrate.core.model.CustomTypeDefinition
import dev.dmigrate.core.model.CustomTypeKind
import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NeutralTypeCanonicalizer
import dev.dmigrate.driver.metadata.DomainTypes
import dev.dmigrate.driver.metadata.SchemaReaderUtils

/**
 * PostgreSQL neutral-type canonicaliser as the live composition of the
 * driver's own forward and reverse type mappings: `canonicalize(t) =
 * reverse(toSql(t))`. [toColumnInput] bridges the rendered DDL spelling into
 * the information_schema spelling [PostgresTypeMapping.mapColumn] consumes
 * (`VARCHAR(n)` → `character varying`, `TIMESTAMP` → `timestamp without time
 * zone`, `X[]` → `array` + `_x` udt, …) — a purely mechanical spelling map;
 * the mapping SEMANTICS stay single-sourced in [PostgresTypeMapping].
 * Reverse notes/generation are discarded (type-only projection), EXCEPT the
 * unknown-type fallback (R301): an unbridged spelling returns the input
 * unchanged, so a gap in the bridge surfaces as loud drift instead of a
 * silent false type-equivalence.
 *
 * Identity carve-outs — only where the round trip carries fidelity the
 * DDL type string cannot transport: [NeutralType.Geometry] (subtype + SRID
 * travel through PostGIS metadata), [NeutralType.Identifier] with
 * `autoIncrement` (SERIAL reverses PK-context-dependently), and
 * [NeutralType.Enum] with `refType` (custom-type path, not the inline TEXT
 * degradation). Everything else — including `fulltext` (tsvector round-trips
 * faithfully) and arrays — goes through the composition.
 *
 * The context-free form keeps `Enum(refType)` as is. With the custom types of the schema
 * (`canonicalize(type, customTypes)`) a **domain** column folds onto its base type, so the file side and the
 * database side hash alike whether a column is spelled `ref_type` or as the base type.
 */
internal object PostgresNeutralTypeCanonicalizer : NeutralTypeCanonicalizer {

    private val typeMapper = PostgresTypeMapper()
    private val parenContent = Regex("""\([^)]*\)""")
    private val ARRAY_ELEMENTS = setOf("integer", "biginteger", "text", "boolean", "uuid", "float", "decimal", "json")

    /**
     * Eine Spalte vom Typ einer Domain wird zu ihrem Basistyp: der Fingerabdruck sieht die Domain-Spalte wie die
     * Basistyp-Spalte, so wie er sie vor dem `ref_type` des Readers sah. Eine Enum und eine Domain, deren
     * Basistyp kein neutrales Gegenstueck hat (Domain ueber einer Domain, `varchar(n)[]`), bleiben stehen.
     */
    override fun canonicalize(type: NeutralType, customTypes: Map<String, CustomTypeDefinition>): NeutralType {
        val domain = (type as? NeutralType.Enum)?.refType?.let { customTypes[it] }
            ?.takeIf { it.kind == CustomTypeKind.DOMAIN }
        return canonicalize(domain?.let(::domainBaseType) ?: type)
    }

    /**
     * Der Basistyp einer Domain als neutraler Typ, ueber die Neutralform des Typvergleichs
     * ([CustomTypeBaseType]): Datei und Reader schreiben ihn verschieden (`real` und `float`, `int4[]` und
     * `integer[]`), zwei Domains, die der Vergleich gleich nennt, falten auf denselben Typ.
     */
    private fun domainBaseType(domain: CustomTypeDefinition): NeutralType? {
        val base = CustomTypeBaseType.canonical(domain.baseType ?: "text") ?: return null
        if (base.endsWith("[]")) return base.removeSuffix("[]").takeIf { it in ARRAY_ELEMENTS }?.let { NeutralType.Array(it) }
        return DomainTypes.resolveBaseType(base, CustomTypeBaseType.precision(domain), CustomTypeBaseType.scale(domain))
    }

    override fun canonicalize(type: NeutralType): NeutralType = when {
        type is NeutralType.Geometry -> type
        type is NeutralType.Identifier && type.autoIncrement -> type
        type is NeutralType.Enum && type.refType != null -> type
        else -> {
            val mapped = PostgresTypeMapping.mapColumn(toColumnInput(typeMapper.toSql(type)))
            if (mapped.note?.code == "R301") type else mapped.type
        }
    }

    private fun toColumnInput(rendered: String): PostgresTypeMapping.ColumnInput {
        val upper = rendered.uppercase()
        val (precision, scale) = SchemaReaderUtils.parenPrecisionScale(upper)
        val isArray = upper.endsWith("[]")
        val base = parenContent.replace(upper.removeSuffix("[]"), "").trim()
        return PostgresTypeMapping.ColumnInput(
            dataType = if (isArray) "array" else infoSchemaSpelling(base),
            udtName = if (isArray) "_${elementUdtName(base)}" else "",
            isPkCol = false,
            isIdentity = false,
            identityGeneration = null,
            colDefault = null,
            generatedSequenceName = null,
            charMaxLen = SchemaReaderUtils.parenLength(upper),
            numPrecision = precision,
            numScale = scale,
            tableName = "",
            colName = "",
        )
    }

    /** DDL-Rendering → information_schema-`data_type`-Schreibweise. */
    private fun infoSchemaSpelling(ddlBase: String): String = when (ddlBase) {
        "VARCHAR" -> "character varying"
        "CHAR" -> "character"
        "DECIMAL" -> "numeric"
        "TIMESTAMP" -> "timestamp without time zone"
        "TIMESTAMP WITH TIME ZONE" -> "timestamp with time zone"
        "TIME" -> "time without time zone"
        else -> ddlBase.lowercase()
    }

    /**
     * Element-DDL-Rendering → udt-Name. Geschlossener Satz: `elementSql` im
     * [PostgresTypeMapper] rendert genau die Elementarten, die
     * [PostgresTypeMapping.mapArrayElementType] benennt; alles andere bleibt
     * `TEXT[]`. Die beiden Seiten gehören zusammen — wer eine erweitert,
     * erweitert die andere mit, sonst projiziert der Kanonisierer ein Element
     * auf `text`, das der Reverse richtig liest.
     */
    private fun elementUdtName(elementDdl: String): String = when (elementDdl) {
        "INTEGER" -> "int4"
        "BIGINT" -> "int8"
        "BOOLEAN" -> "bool"
        "UUID" -> "uuid"
        "DOUBLE PRECISION" -> "float8"
        "NUMERIC" -> "numeric"
        "JSONB" -> "jsonb"
        else -> "text"
    }
}
