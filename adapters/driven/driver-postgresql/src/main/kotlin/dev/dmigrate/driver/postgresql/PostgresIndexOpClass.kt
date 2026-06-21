package dev.dmigrate.driver.postgresql

import dev.dmigrate.core.model.IndexDefinition
import dev.dmigrate.core.model.IndexType
import dev.dmigrate.core.model.NeutralType

/**
 * VA3: PostgreSQL-Zugriffsmethode (`USING …`) für einen neutralen [IndexType].
 * Der neutrale räumliche Index [IndexType.SPATIAL] wird in PostGIS als `GIST`
 * realisiert; alle anderen Typen tragen ihren Enum-Namen. Einzige Quelle für die
 * PG-Index-Methode, geteilt von [PostgresDdlGenerator] und [PostgresDiffSqlBuilders].
 *
 * Empirisch gegen PostGIS belegt: `USING SPATIAL` existiert in PostgreSQL **nicht**
 * (`access method "spatial" does not exist`); der `geometry`-Typ trägt eine
 * Default-Operatorklasse für `gist` (sowie `spgist`/`brin`), und `gist` ist die
 * idiomatische räumliche Zugriffsmethode in PostGIS. Daher ist das Mapping zwingend.
 */
internal fun pgAccessMethod(type: IndexType): String =
    if (type == IndexType.SPATIAL) "GIST" else type.name

/**
 * VA3: ob [type] eine in PostGIS gültige *räumliche* Zugriffsmethode für eine
 * Geometriespalte ist. GiST (Default), SP-GiST und BRIN sind die unterstützten
 * räumlichen Methoden; der neutrale [IndexType.SPATIAL] wird auf GiST abgebildet.
 * B-Tree/HASH/GIN sind ausgeschlossen — B-Tree taugt nur für Equality/Sortierung,
 * nicht für räumliche Suche (Schnittmenge/Distanz). PostGIS-Doku-belegt.
 */
internal fun pgSupportsGeometryIndex(type: IndexType): Boolean =
    type == IndexType.GIST || type == IndexType.SPGIST ||
        type == IndexType.BRIN || type == IndexType.SPATIAL

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
     * The first indexed column whose type lacks a default GIN/GIST/SP-GiST operator
     * class, or null when the index is renderable (other access methods, or every
     * column has a default operator class). [columnType] resolves a column name
     * to its neutral type (null when unknown).
     */
    fun missingOpClassColumn(index: IndexDefinition, columnType: (String) -> NeutralType?): String? {
        if (index.type != IndexType.GIN && index.type != IndexType.GIST && index.type != IndexType.SPGIST) {
            return null
        }
        return index.columnNames.firstOrNull { name ->
            val type = columnType(name) ?: return@firstOrNull false
            !hasDefaultOpClass(index.type, type)
        }
    }

    private fun hasDefaultOpClass(indexType: IndexType, type: NeutralType): Boolean = when (indexType) {
        IndexType.GIN -> type is NeutralType.Json || type is NeutralType.Array
        // ADR 0015: FullText (tsvector) has the tsvector_ops default GiST
        // operator class, so a GiST index on it is renderable (no W123 skip).
        IndexType.GIST -> type is NeutralType.Geometry || type is NeutralType.FullText
        // VA3: SP-GiST hat eine geometry-Default-Operatorklasse (aber keine für
        // tsvector); empirisch gegen PostGIS belegt (geometry: gist+spgist+brin+btree).
        IndexType.SPGIST -> type is NeutralType.Geometry
        else -> true
    }
}
