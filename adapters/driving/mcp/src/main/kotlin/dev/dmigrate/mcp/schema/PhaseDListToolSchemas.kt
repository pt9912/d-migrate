package dev.dmigrate.mcp.schema

/**
 * Phase-D §10.5 + §6.4 typed list-tool schemas.
 *
 * Five `*_list` discovery tools — `job_list`, `artifact_list`,
 * `schema_list`, `profile_list`, `diff_list` — share the
 * `tenantId` / `pageSize` / `cursor` / time-window-filter input
 * surface from [listInputCommon] and the §6.2 wire response
 * shape (typed collection field + `nextCursor` + optional totals)
 * from [listOutputObj]. Each tool publishes a per-resource
 * `*ListItem` shape that pins the §6.4 minimum-field set.
 *
 * Lives outside [PhaseBToolSchemas] so the registry registry
 * stays under the LargeClass threshold and the §10.5 contract
 * has a single, focused home.
 */
internal object PhaseDListToolSchemas {

    /**
     * Returns the five `*_list` schema pairs keyed by tool name.
     * [PhaseBToolSchemas] merges this map into the central
     * `SCHEMAS` table so `forTool(...)` continues to surface
     * Phase-D tools through the same lookup API.
     */
    fun allPairs(): Map<String, SchemaPair> = mapOf(
        "job_list" to jobListPair(),
        "artifact_list" to artifactListPair(),
        "schema_list" to schemaListPair(),
        "profile_list" to profileListPair(),
        "diff_list" to diffListPair(),
    )

    /**
     * Common input fields every Phase-D list tool advertises:
     * `tenantId`, `pageSize`, `cursor`, plus inclusive time-window
     * filters. Resource-specific filter fields are merged in by
     * the per-tool builders below.
     */
    private fun listInputCommon(): Map<String, Map<String, Any>> = mapOf(
        "tenantId" to stringField(),
        "pageSize" to mapOf("type" to "integer", "minimum" to 1),
        "cursor" to stringField(),
        "createdAfter" to stringField(),
        "createdBefore" to stringField(),
    )

    /**
     * Common output shape for list tools: typed collection field +
     * opaque `nextCursor` + optional totals. `additionalProperties=false`
     * forbids ad-hoc `items` fallback.
     */
    private fun listOutputObj(
        collectionField: String,
        itemSchema: Map<String, Any>,
    ): Map<String, Any> = obj(
        collectionField to mapOf("type" to "array", "items" to itemSchema),
        "nextCursor" to stringField(),
        "totalCount" to integerField(),
        "totalCountEstimate" to booleanField(),
    ).required(collectionField)

    private fun jobListPair(): SchemaPair = schemaPair(
        input = obj(
            *(
                listInputCommon() + mapOf(
                    "status" to enumField("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"),
                    "operation" to stringField(),
                )
                ).entries.map { it.key to it.value }.toTypedArray(),
        ).build(),
        output = listOutputObj("jobs", jobListItem()),
    )

    private fun artifactListPair(): SchemaPair = schemaPair(
        input = obj(
            *(
                listInputCommon() + mapOf(
                    "kind" to enumField("SCHEMA", "PROFILE", "DIFF", "DATA_EXPORT", "UPLOAD_INPUT", "OTHER"),
                    "jobId" to stringField(),
                )
                ).entries.map { it.key to it.value }.toTypedArray(),
        ).build(),
        output = listOutputObj("artifacts", artifactListItem()),
    )

    private fun schemaListPair(): SchemaPair = schemaPair(
        input = obj(
            *(
                listInputCommon() + mapOf(
                    "jobId" to stringField(),
                )
                ).entries.map { it.key to it.value }.toTypedArray(),
        ).build(),
        output = listOutputObj("schemas", schemaListItem()),
    )

    private fun profileListPair(): SchemaPair = schemaPair(
        input = obj(
            *(
                listInputCommon() + mapOf(
                    "jobId" to stringField(),
                )
                ).entries.map { it.key to it.value }.toTypedArray(),
        ).build(),
        output = listOutputObj("profiles", profileListItem()),
    )

    private fun diffListPair(): SchemaPair = schemaPair(
        input = obj(
            *(
                listInputCommon() + mapOf(
                    "jobId" to stringField(),
                    "sourceRef" to stringField(),
                    "targetRef" to stringField(),
                )
                ).entries.map { it.key to it.value }.toTypedArray(),
        ).build(),
        output = listOutputObj("diffs", diffListItem()),
    )

    /**
     * §6.4 minimum fields per `jobs[]` entry. `artifactUris` is
     * optional so a job with no artefacts still validates.
     */
    private fun jobListItem(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf(
            "jobId" to stringField(),
            "tenantId" to stringField(),
            "status" to enumField("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"),
            "operation" to stringField(),
            "resourceUri" to jobResourceUriField(),
            "createdAt" to stringField(),
            "updatedAt" to stringField(),
            "expiresAt" to stringField(),
            "visibilityClass" to enumField("OWN", "TENANT_VISIBLE", "ADMIN_VISIBLE"),
            "artifactUris" to mapOf("type" to "array", "items" to artifactRefField()),
        ),
        "required" to listOf(
            "jobId", "tenantId", "status", "operation", "resourceUri", "createdAt", "updatedAt",
        ),
    )

    /** §6.4 minimum fields per `artifacts[]` entry. */
    private fun artifactListItem(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf(
            "artifactId" to stringField(),
            "tenantId" to stringField(),
            "artifactKind" to enumField("SCHEMA", "PROFILE", "DIFF", "DATA_EXPORT", "UPLOAD_INPUT", "OTHER"),
            "jobId" to stringField(),
            "filename" to stringField(),
            "sizeBytes" to integerField(),
            "contentType" to stringField(),
            "resourceUri" to artifactRefField(),
            "chunkTemplate" to stringField(),
            "createdAt" to stringField(),
            "expiresAt" to stringField(),
            "visibilityClass" to enumField("OWN", "TENANT_VISIBLE", "ADMIN_VISIBLE"),
        ),
        "required" to listOf(
            "artifactId", "tenantId", "artifactKind", "filename", "sizeBytes", "contentType", "resourceUri",
        ),
    )

    /**
     * Plan-D §6.4 minimum fields per `schemas[]` entry. Phase-D
     * mandates `format`, `origin`, `sizeBytes` plus optional
     * `hash` alongside the common identity fields. Producers may
     * not yet set every metadata field, so the wire-shape allows
     * `null` (string|null / integer|null) — Schema-validating
     * clients still see the keys as `required`.
     */
    private fun schemaListItem(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf(
            "schemaId" to stringField(),
            "tenantId" to stringField(),
            "displayName" to stringField(),
            "artifactRef" to artifactRefField(),
            "resourceUri" to mapOf(
                "type" to "string",
                "pattern" to """^dmigrate://tenants/[^/]+/schemas/[^/]+$""",
            ),
            "jobId" to nullableStringField(),
            "format" to nullableStringField(),
            "origin" to nullableStringField(),
            "sizeBytes" to nullableIntegerField(),
            "hash" to nullableStringField(),
            "createdAt" to stringField(),
            "expiresAt" to stringField(),
        ),
        "required" to listOf(
            "schemaId", "tenantId", "resourceUri",
            "format", "origin", "sizeBytes",
            "createdAt",
        ),
    )

    /**
     * Plan-D §6.4 minimum fields per `profiles[]` entry. Phase-D
     * mandates `connectionRef` (or `connectionResourceUri`),
     * `scope` plus optional `warningCount` alongside the common
     * identity fields.
     */
    private fun profileListItem(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf(
            "profileId" to stringField(),
            "tenantId" to stringField(),
            "displayName" to stringField(),
            "artifactRef" to artifactRefField(),
            "resourceUri" to mapOf(
                "type" to "string",
                "pattern" to """^dmigrate://tenants/[^/]+/profiles/[^/]+$""",
            ),
            "jobId" to nullableStringField(),
            "connectionRef" to nullableStringField(),
            "scope" to nullableStringField(),
            "warningCount" to nullableIntegerField(),
            "createdAt" to stringField(),
            "expiresAt" to stringField(),
        ),
        "required" to listOf(
            "profileId", "tenantId", "resourceUri",
            "connectionRef", "scope",
            "createdAt",
        ),
    )

    /**
     * Plan-D §6.4 minimum fields per `diffs[]` entry. The plan
     * uses the wire-names `leftSchemaId` / `rightSchemaId` (the
     * model field is `sourceRef` / `targetRef` for stage helper
     * compatibility — projector renames at the wire boundary).
     */
    private fun diffListItem(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf(
            "diffId" to stringField(),
            "tenantId" to stringField(),
            "displayName" to stringField(),
            "artifactRef" to artifactRefField(),
            "resourceUri" to mapOf(
                "type" to "string",
                "pattern" to """^dmigrate://tenants/[^/]+/diffs/[^/]+$""",
            ),
            "leftSchemaId" to stringField(),
            "rightSchemaId" to stringField(),
            "statusSummary" to nullableStringField(),
            "jobId" to nullableStringField(),
            "createdAt" to stringField(),
            "expiresAt" to stringField(),
        ),
        "required" to listOf(
            "diffId", "tenantId", "resourceUri",
            "leftSchemaId", "rightSchemaId", "statusSummary",
            "createdAt",
        ),
    )
}
