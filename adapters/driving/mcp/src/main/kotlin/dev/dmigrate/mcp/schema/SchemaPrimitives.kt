package dev.dmigrate.mcp.schema

/**
 * Low-level JSON-Schema primitives shared by [PhaseBToolSchemas]
 * and [PhaseDListToolSchemas]. Lives at top-level of the schema
 * package so both schema registries can use the same builders
 * without one importing internal members of the other — keeps
 * each registry under the detekt LargeClass / TooManyFunctions
 * thresholds while staying lookup-by-tool-name addressable.
 *
 * Conventions:
 * - All builders return `Map<String, Any>` — the canonical wire
 *   shape Gson serialises into the published `outputSchema` /
 *   `inputSchema` slots.
 * - Closed-shape default: top-level `obj()` builders set
 *   `additionalProperties=false` so unknown keys fail
 *   JSON-Schema validation (Plan-D §6.1 strict-property
 *   contract).
 */

/**
 * Tool input/output schema bundle. Top-level so both schema
 * registries surface the same record shape; named [SchemaPair]
 * (rather than a bare `Pair`) so callers don't have to
 * disambiguate against `kotlin.Pair`.
 */
internal data class SchemaPair(
    val inputSchema: Map<String, Any>,
    val outputSchema: Map<String, Any>,
)

internal fun schemaPair(input: Map<String, Any>, output: Map<String, Any>): SchemaPair =
    SchemaPair(inputSchema = input, outputSchema = output)

internal fun stringField(): Map<String, Any> = mapOf("type" to "string")

/**
 * AP D9 review: a string-or-null field for wire fields that the
 * runtime emits as explicit `null` on terminal states (e.g.
 * `artifact_chunk_get.nextChunkUri` / `.nextChunkCursor` on the
 * last chunk). JSON-Schema 2020-12 type-arrays are the idiomatic
 * spelling.
 */
internal fun nullableStringField(): Map<String, Any> = mapOf("type" to listOf("string", "null"))

internal fun booleanField(): Map<String, Any> = mapOf("type" to "boolean")

internal fun integerField(minimum: Int = 0): Map<String, Any> =
    mapOf("type" to "integer", "minimum" to minimum)

/**
 * Plan-D §6.4 review: nullable integer field for size-bytes /
 * warning-count fields that the producer may not yet record.
 * Schema-validating clients see the slot as required+nullable.
 */
internal fun nullableIntegerField(): Map<String, Any> = mapOf("type" to listOf("integer", "null"))

internal fun objectField(): Map<String, Any> =
    mapOf("type" to "object", "additionalProperties" to true)

internal fun arrayField(itemType: String? = null): Map<String, Any> = if (itemType == null) {
    mapOf("type" to "array")
} else {
    mapOf("type" to "array", "items" to mapOf("type" to itemType))
}

internal fun enumField(vararg values: String): Map<String, Any> = mapOf(
    "type" to "string",
    "enum" to values.toList(),
)

internal fun emptyObject(): Map<String, Any> = mapOf(
    JsonSchemaDialect.SCHEMA_KEYWORD to JsonSchemaDialect.SCHEMA_URI,
    "type" to "object",
    "additionalProperties" to false,
)

internal fun obj(vararg fields: Pair<String, Map<String, Any>>): SchemaBuilder =
    SchemaBuilder(properties = fields.toMap())

/**
 * Mutable builder for object-type schemas. [required] and [build]
 * are terminal — they return the assembled `Map<String, Any>`
 * directly. AP 6.23 adds [withAllOf] for cross-field constraints
 * (e.g. `if truncated=true then artifactRef required`). Chain
 * `withAllOf(...)` before the terminal call:
 *
 * ```
 * obj("a" to ..., "b" to ...)
 *     .withAllOf(truncatedRequiresField("artifactRef"))
 *     .required("a")
 * ```
 */
internal class SchemaBuilder(
    private val properties: Map<String, Map<String, Any>>,
) {
    private val allOf = mutableListOf<Map<String, Any>>()

    fun withAllOf(constraint: Map<String, Any>): SchemaBuilder = apply {
        allOf.add(constraint)
    }

    fun required(vararg names: String): Map<String, Any> = assemble(names.toList())

    fun build(): Map<String, Any> = assemble(emptyList())

    private fun assemble(required: List<String>): Map<String, Any> = buildMap {
        put(JsonSchemaDialect.SCHEMA_KEYWORD, JsonSchemaDialect.SCHEMA_URI)
        put("type", "object")
        put("additionalProperties", false)
        if (properties.isNotEmpty()) put("properties", properties)
        if (required.isNotEmpty()) put("required", required)
        if (allOf.isNotEmpty()) put("allOf", allOf.toList())
    }
}

/**
 * AP 6.23 shared resource-URI pattern for
 * `dmigrate://tenants/{tenantId}/artifacts/{artifactId}` — used by
 * `artifactRef`, `diffArtifactRef`, and the per-element shape
 * inside `job_status_get.artifacts[]`. Tenant- and artefact-id
 * segments are non-empty and contain no `/`.
 *
 * Note: this is a STRUCTURAL match, not an authoritative trust
 * boundary. `[^/]+` accepts URL-encoded bytes / Unicode / control
 * characters that the upstream `ServerResourceUri.parse` would
 * reject. The schema-level guarantee is "looks like a tenant-
 * scoped artefact URI"; the canonical validation lives in
 * `ServerResourceUri`.
 */
internal const val ARTIFACT_REF_PATTERN: String =
    """^dmigrate://tenants/[^/]+/artifacts/[^/]+$"""

/** Closed-shape `artifactRef` field with the [ARTIFACT_REF_PATTERN]. */
internal fun artifactRefField(): Map<String, Any> = mapOf(
    "type" to "string",
    "pattern" to ARTIFACT_REF_PATTERN,
)

/**
 * Job-resource URI pattern for `dmigrate://tenants/{tenantId}/jobs/{jobId}`.
 * Distinct from [ARTIFACT_REF_PATTERN] so a `resourceUri` field
 * accidentally pointing at an artefact fails schema validation.
 */
internal const val JOB_RESOURCE_URI_PATTERN: String =
    """^dmigrate://tenants/[^/]+/jobs/[^/]+$"""

internal fun jobResourceUriField(): Map<String, Any> = mapOf(
    "type" to "string",
    "pattern" to JOB_RESOURCE_URI_PATTERN,
)
