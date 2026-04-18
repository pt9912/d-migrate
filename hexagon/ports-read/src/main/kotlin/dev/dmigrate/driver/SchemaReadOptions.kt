package dev.dmigrate.driver

/**
 * Options controlling which database object types to include when
 * reading a live schema via [SchemaReader].
 *
 * All flags default to `true`. CLI/IO concerns like output path,
 * format, or report mode do NOT belong here — they are application-layer
 * concerns.
 *
 * Dialect selection is also not part of these options because the dialect
 * is determined by the chosen [DatabaseDriver].
 */
data class SchemaReadOptions(
    val includeViews: Boolean = true,
    val includeProcedures: Boolean = true,
    val includeFunctions: Boolean = true,
    val includeTriggers: Boolean = true,
)
