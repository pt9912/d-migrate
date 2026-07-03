package dev.dmigrate.driver.mysql

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NeutralTypeCanonicalizer

/**
 * MySQL neutral-type canonicaliser as the live composition of the driver's
 * own forward and reverse type mappings: `canonicalize(t) =
 * reverse(toSql(t))`. [toColumnInput] bridges the rendered DDL string into
 * the metadata shape [MysqlTypeMapping.mapColumn] consumes (MySQL's
 * information_schema `data_type` is the DDL token, so the bridge is purely
 * mechanical). Reverse notes/generation are discarded (type-only projection).
 *
 * Identity carve-outs: [NeutralType.Geometry]/[NeutralType.FullText] carry
 * their fidelity outside the declared column type; [NeutralType.Enum] with
 * `refType` is the custom-type path, not the inline TEXT degradation the
 * migrate path renders.
 */
internal object MysqlNeutralTypeCanonicalizer : NeutralTypeCanonicalizer {

    private val typeMapper = MysqlTypeMapper()
    private val singleLength = Regex("""\((\d+)\)""")
    private val precisionScale = Regex("""\((\d+)\s*,\s*(\d+)\)""")

    override fun canonicalize(type: NeutralType): NeutralType = when {
        type is NeutralType.Geometry || type == NeutralType.FullText -> type
        type is NeutralType.Enum && type.refType != null -> type
        else -> MysqlTypeMapping.mapColumn(toColumnInput(typeMapper.toSql(type))).type
    }

    private fun toColumnInput(rendered: String): MysqlTypeMapping.ColumnInput {
        val lower = rendered.lowercase()
        val ps = precisionScale.find(lower)
        return MysqlTypeMapping.ColumnInput(
            dataType = lower.substringBefore('(').trim().substringBefore(' '),
            columnType = lower,
            isAutoIncrement = lower.contains("auto_increment"),
            charMaxLen = if (ps == null) singleLength.find(lower)?.groupValues?.get(1)?.toIntOrNull() else null,
            numPrecision = ps?.groupValues?.get(1)?.toIntOrNull(),
            numScale = ps?.groupValues?.get(2)?.toIntOrNull(),
            tableName = "",
            colName = "",
        )
    }
}
