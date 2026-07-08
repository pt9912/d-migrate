package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NeutralTypeCanonicalizer
import dev.dmigrate.driver.metadata.SchemaReaderUtils

/**
 * MySQL neutral-type canonicaliser as the live composition of the driver's
 * own forward and reverse type mappings: `canonicalize(t) =
 * reverse(toSql(t))`. [toColumnInput] bridges the rendered DDL string into
 * the metadata shape [MysqlTypeMapping.mapColumn] consumes (MySQL's
 * information_schema `data_type` is the DDL token, so the bridge is purely
 * mechanical). Reverse notes/generation are discarded (type-only
 * projection), EXCEPT the unknown-type fallback (R301): an unbridged
 * spelling returns the input unchanged, so a gap surfaces as loud drift
 * instead of a silent false type-equivalence.
 *
 * Identity carve-outs — only where the round trip carries fidelity the DDL
 * type string cannot transport: [NeutralType.Geometry] (subtype + SRID
 * travel through the column SRID attribute) and [NeutralType.Enum] with
 * `refType` (custom-type path). Inline enums and `fulltext` go through the
 * composition — the migrate path renders both as TEXT (AP0-belegt; die
 * generate/migrate-Enum-Divergenz trackt
 * `docs/planning/open/enum-generate-silent-degradation.md`).
 */
internal object MysqlNeutralTypeCanonicalizer : NeutralTypeCanonicalizer {

    private val typeMapper = MysqlTypeMapper()

    override fun canonicalize(type: NeutralType): NeutralType = when {
        type is NeutralType.Geometry -> type
        type is NeutralType.Enum && type.refType != null -> type
        else -> {
            val mapped = MysqlTypeMapping.mapColumn(toColumnInput(typeMapper.toSql(type)))
            if (mapped.note?.code == "R301") type else mapped.type
        }
    }

    private fun toColumnInput(rendered: String): MysqlTypeMapping.ColumnInput {
        val lower = rendered.lowercase()
        val (precision, scale) = SchemaReaderUtils.parenPrecisionScale(lower)
        return MysqlTypeMapping.ColumnInput(
            dataType = lower.substringBefore('(').trim().substringBefore(' '),
            columnType = lower,
            isAutoIncrement = lower.contains("auto_increment"),
            charMaxLen = SchemaReaderUtils.parenLength(lower),
            numPrecision = precision,
            numScale = scale,
            tableName = "",
            colName = "",
        )
    }
}
