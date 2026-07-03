package dev.dmigrate.driver.sqlite

import dev.dmigrate.core.model.NeutralType
import dev.dmigrate.driver.NeutralTypeCanonicalizer

/**
 * SQLite neutral-type canonicaliser as the live composition of the driver's
 * own forward and reverse type mappings: `canonicalize(t) =
 * reverse(toSql(t))`. The projection IS the real round trip by construction —
 * there is no second flattening table to keep in sync with
 * [SqliteTypeMapper]/[SqliteTypeMapping]. Reverse notes are discarded (the
 * projection is type-only).
 *
 * [NeutralType.Geometry] and [NeutralType.FullText] stay identity: their
 * fidelity travels outside the declared column type (`AddGeometryColumn`
 * metadata resp. FTS5 objects), so folding them onto the declared storage
 * type would erase drift sensitivity the reverse actually has.
 */
internal object SqliteNeutralTypeCanonicalizer : NeutralTypeCanonicalizer {

    private val typeMapper = SqliteTypeMapper()

    override fun canonicalize(type: NeutralType): NeutralType = when (type) {
        is NeutralType.Geometry, NeutralType.FullText -> type
        else -> {
            val rendered = typeMapper.toSql(type)
            SqliteTypeMapping.mapColumn(
                rawType = rendered,
                isAutoIncrement = rendered.contains("AUTOINCREMENT"),
                tableName = "",
                colName = "",
            ).type
        }
    }
}
