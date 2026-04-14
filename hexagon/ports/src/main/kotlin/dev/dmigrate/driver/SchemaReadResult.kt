package dev.dmigrate.driver

import dev.dmigrate.core.model.SchemaDefinition

/**
 * Result of a live schema read via [SchemaReader].
 *
 * Unlike a naked [SchemaDefinition], this envelope carries structured
 * notes (best-effort mappings, warnings) and deliberately skipped
 * objects alongside the schema itself.
 *
 * This type is intentionally separate from [DdlResult]: the reverse
 * path must not carry SQL statement containers or other generator-side
 * artifacts.
 */
data class SchemaReadResult(
    val schema: SchemaDefinition,
    val notes: List<SchemaReadNote> = emptyList(),
    val skippedObjects: List<SkippedObject> = emptyList(),
)
