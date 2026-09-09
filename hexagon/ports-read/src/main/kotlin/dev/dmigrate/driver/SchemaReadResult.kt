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
    /**
     * Version des gelesenen Servers, sofern die Implementierung eine
     * Verbindung hat und der Dialekt sie ausweist. Die Renderer, die davon
     * abhaengen, fangen die fuer sie passende Auspraegung mit `as?` ab
     * ([MysqlServerVersion] fuer die Routinen-Gates, [OracleServerVersion]
     * fuer die Ruecknahme-Klausel). File-only bleibt sie `null` — und damit
     * gilt dort die jeweils konservative Form.
     */
    val serverVersion: ServerVersion? = null,
)
