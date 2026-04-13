package dev.dmigrate.core.model

/**
 * Canonical value type for `geometry_type` in Spatial Phase 1.
 *
 * Design decisions (ImpPlan-0.5.5-B §4.1):
 * - Known values are centrally defined via [KNOWN_VALUES]
 * - Unknown values are preserved losslessly so that [SchemaValidator]
 *   can produce E120 rather than failing at parse time
 * - Default is [GEOMETRY] (arbitrary geometry)
 */
data class GeometryType(val schemaName: String) {

    fun isKnown(): Boolean = schemaName in KNOWN_VALUES

    companion object {
        val KNOWN_VALUES = setOf(
            "geometry",
            "point",
            "linestring",
            "polygon",
            "multipoint",
            "multilinestring",
            "multipolygon",
            "geometrycollection",
        )

        val GEOMETRY = GeometryType("geometry")

        fun of(name: String?): GeometryType =
            if (name.isNullOrBlank()) GEOMETRY else GeometryType(name)
    }

    override fun toString(): String = schemaName
}
