package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NeutralTypeCanonicalizer

/**
 * PostgreSQL neutral-type canonicaliser as the live composition of the
 * driver's own forward and reverse type mappings: `canonicalize(t) =
 * reverse(toSql(t))`. [toColumnInput] bridges the rendered DDL spelling into
 * the information_schema spelling [PostgresTypeMapping.mapColumn] consumes
 * (`VARCHAR(n)` → `character varying`, `TIMESTAMP` → `timestamp without time
 * zone`, …) — a purely mechanical spelling map; the mapping SEMANTICS stay
 * single-sourced in [PostgresTypeMapping]. Reverse notes/generation are
 * discarded (type-only projection).
 *
 * Identity carve-outs: [NeutralType.Geometry]/[NeutralType.FullText] carry
 * their fidelity outside resp. faithfully within the declared type;
 * [NeutralType.Identifier] round-trips PK-context-dependently (SERIAL — the
 * reverse reconstructs it only for PK columns), [NeutralType.Array]
 * round-trips faithfully (AP0-belegt), and [NeutralType.Enum] with `refType`
 * is the custom-type path, not the inline TEXT degradation.
 */
internal object PostgresNeutralTypeCanonicalizer : NeutralTypeCanonicalizer {

    private val typeMapper = PostgresTypeMapper()
    private val singleLength = Regex("""\((\d+)\)""")
    private val precisionScale = Regex("""\((\d+)\s*,\s*(\d+)\)""")
    private val parenContent = Regex("""\([^)]*\)""")

    override fun canonicalize(type: NeutralType): NeutralType = when {
        type is NeutralType.Geometry || type == NeutralType.FullText -> type
        type is NeutralType.Identifier || type is NeutralType.Array -> type
        type is NeutralType.Enum && type.refType != null -> type
        else -> PostgresTypeMapping.mapColumn(toColumnInput(typeMapper.toSql(type))).type
    }

    private fun toColumnInput(rendered: String): PostgresTypeMapping.ColumnInput {
        val upper = rendered.uppercase()
        val ps = precisionScale.find(upper)
        return PostgresTypeMapping.ColumnInput(
            dataType = infoSchemaSpelling(parenContent.replace(upper, "").trim()),
            udtName = "",
            isPkCol = false,
            isIdentity = false,
            identityGeneration = null,
            colDefault = null,
            generatedSequenceName = null,
            charMaxLen = if (ps == null) singleLength.find(upper)?.groupValues?.get(1)?.toIntOrNull() else null,
            numPrecision = ps?.groupValues?.get(1)?.toIntOrNull(),
            numScale = ps?.groupValues?.get(2)?.toIntOrNull(),
            tableName = "",
            colName = "",
        )
    }

    /** DDL-Rendering → information_schema-`data_type`-Schreibweise. */
    private fun infoSchemaSpelling(ddlBase: String): String = when (ddlBase) {
        "VARCHAR" -> "character varying"
        "CHAR" -> "character"
        "DECIMAL", "NUMERIC" -> "numeric"
        "TIMESTAMP" -> "timestamp without time zone"
        "TIMESTAMP WITH TIME ZONE" -> "timestamp with time zone"
        "TIME" -> "time without time zone"
        else -> ddlBase.lowercase()
    }
}
