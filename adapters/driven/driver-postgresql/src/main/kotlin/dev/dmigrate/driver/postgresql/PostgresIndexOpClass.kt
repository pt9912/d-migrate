package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType

/**
 * I-08: PostgreSQL default operator-class rules for the GIN/GIST access methods.
 *
 * A column whose type has no default operator class (e.g. a `tsvector` column
 * degraded to `text` on reverse) cannot back a `USING gin/gist` index — PG
 * rejects it with "data type … has no default operator class for access method".
 * Shared by the generate path ([PostgresDdlGenerator]) and the diff path
 * ([PostgresDiffRenderContext]) so the rule has a single source of truth.
 */
internal object PostgresIndexOpClass {

    /**
     * The first indexed column whose type lacks a default GIN/GIST operator
     * class, or null when the index is renderable (non-GIN/GIST, or every
     * column has a default operator class). [columnType] resolves a column name
     * to its neutral type (null when unknown).
     */
    fun missingOpClassColumn(index: IndexDefinition, columnType: (String) -> NeutralType?): String? {
        if (index.type != IndexType.GIN && index.type != IndexType.GIST) return null
        return index.columnNames.firstOrNull { name ->
            val type = columnType(name) ?: return@firstOrNull false
            !hasDefaultOpClass(index.type, type)
        }
    }

    private fun hasDefaultOpClass(indexType: IndexType, type: NeutralType): Boolean = when (indexType) {
        IndexType.GIN -> type is NeutralType.Json || type is NeutralType.Array
        IndexType.GIST -> type is NeutralType.Geometry
        else -> true
    }
}
