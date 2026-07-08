package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NeutralTypeCanonicalizer

/**
 * SQLite neutral-type canonicaliser as the live composition of the driver's
 * own forward and reverse type mappings: `canonicalize(t) =
 * reverse(toSql(t))`. The projection IS the real round trip by construction —
 * there is no second flattening table to keep in sync with
 * [SqliteTypeMapper]/[SqliteTypeMapping]. Reverse notes are discarded (the
 * projection is type-only), EXCEPT the unknown-type fallback (R201): an
 * unrecognised rendering returns the input unchanged, so a gap surfaces as
 * loud drift instead of a silent false type-equivalence.
 *
 * [NeutralType.Geometry] is the single identity carve-out: its fidelity
 * (subtype/SRID) travels through `AddGeometryColumn` metadata, not the
 * declared column type, and the reverse reconstructs it — folding it onto
 * the declared storage type would erase drift sensitivity the reverse
 * actually has. `fulltext` columns go through the composition: the migrate
 * path degrades them to TEXT (ADR 0015) and the reverse reconstructs only
 * the FULLTEXT *index*, so `Text()` is the storage reality of the column.
 */
internal object SqliteNeutralTypeCanonicalizer : NeutralTypeCanonicalizer {

    private val typeMapper = SqliteTypeMapper()

    override fun canonicalize(type: NeutralType): NeutralType = when (type) {
        is NeutralType.Geometry -> type
        else -> {
            val rendered = typeMapper.toSql(type)
            val mapped = SqliteTypeMapping.mapColumn(
                rawType = rendered,
                isAutoIncrement = SqliteTypeMapping.hasAutoincrement(rendered),
                tableName = "",
                colName = "",
            )
            if (mapped.note?.code == "R201") type else mapped.type
        }
    }
}
