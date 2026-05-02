package dev.dmigrate.mcp.schema

/**
 * Phase B input/output JSON-Schemas for every 0.9.6 tool per
 * `ImpPlan-0.9.6-B.md` §5.6 + §6.10.
 *
 * Each tool gets a typed schema with the obvious top-level
 * arguments. Phase C/D will refine them as handlers come online; the
 * Golden test pins the current shape so any unintentional drift is
 * caught.
 *
 * Conventions (verbindlich):
 * - Every schema sets `$schema` to [JsonSchemaDialect.SCHEMA_URI].
 * - Every schema sets `type: "object"`.
 * - `additionalProperties: false` is the default — protocol-level
 *   strictness so payloads can't smuggle extra fields. Tools that
 *   intentionally accept open-ended metadata flip this to `true`
 *   (currently none).
 * - Property names follow camelCase. The [SchemaSecretGuard] rejects
 *   any name that looks like a credential at build time; schemas
 *   below are written with that constraint in mind.
 */
internal object PhaseBToolSchemas {

    /**
     * Tool input/output schema bundle. Named [SchemaPair] (rather than
     * a bare `Pair`) so callers don't have to disambiguate against
     * Kotlin's `kotlin.Pair` at every reference site.
     */
    data class SchemaPair(
        val inputSchema: Map<String, Any>,
        val outputSchema: Map<String, Any>,
    )

    /** Lookup by tool name. Returns `null` if no schema is registered. */
    fun forTool(name: String): SchemaPair? = SCHEMAS[name]

    /** All registered tool names, deterministic order (alphabetical). */
    fun toolNames(): List<String> = SCHEMAS.keys.sorted()

    private val FORMAT_NAMES: Array<String> =
        dev.dmigrate.format.SchemaFileResolver.SUPPORTED_FORMATS.toTypedArray()

    private val DIALECT_NAMES: Array<String> =
        dev.dmigrate.driver.DatabaseDialect.entries.map { it.name }.toTypedArray()

    private val SCHEMAS: Map<String, SchemaPair> = buildMap {
        // Discovery / contract
        put("capabilities_list", schemaPair(input = emptyObject(), output = capabilitiesOutput()))

        // Read-only schema tools
        put("schema_validate", schemaPair(
            input = obj(
                "schema" to objectField(),
                "schemaRef" to stringField(),
                "format" to enumField(*FORMAT_NAMES),
                "strictness" to enumField(*Strictness.WIRE_VALUES.toTypedArray()),
            ).build(),
            output = obj(
                "valid" to booleanField(),
                "summary" to stringField(),
                "findings" to arrayField(),
                "truncated" to booleanField(),
                "executionMeta" to objectField(),
            ).required("valid", "summary", "findings", "truncated"),
        ))
        put("schema_compare", schemaPair(
            input = obj(
                "sourceUri" to stringField(),
                "targetUri" to stringField(),
            ).required("sourceUri", "targetUri"),
            output = obj(
                "differences" to arrayField(),
                "diffArtifactRef" to stringField(),
            ).build(),
        ))
        put("schema_generate", schemaPair(
            input = obj(
                "schema" to objectField(),
                "schemaRef" to stringField(),
                "format" to enumField(*FORMAT_NAMES),
                "targetDialect" to enumField(*DIALECT_NAMES),
                "spatialProfile" to enumField("postgis", "native", "spatialite", "none"),
                "mysqlNamedSequenceMode" to enumField("action_required", "helper_table"),
            ).required("targetDialect"),
            output = obj(
                "dialect" to stringField(),
                "statementCount" to integerField(),
                "summary" to stringField(),
                "findings" to arrayField(),
                "truncated" to booleanField(),
                "ddl" to stringField(),
                "artifactRef" to stringField(),
                "executionMeta" to objectField(),
            ).required("dialect", "statementCount", "summary", "findings", "truncated"),
        ))
        put("schema_list", listInput("schemas"))
        put("profile_list", listInput("profiles"))
        put("diff_list", listInput("diffs"))
        put("job_list", listInput("jobs"))
        put("artifact_list", listInput("artifacts"))

        put("job_status_get", schemaPair(
            input = obj(
                "jobId" to stringField(),
                "tenantId" to stringField(),
            ).required("jobId"),
            output = obj(
                "jobId" to stringField(),
                "status" to enumField("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"),
                "progress" to objectField(),
                "error" to objectField(),
            ).required("jobId", "status"),
        ))

        put("artifact_chunk_get", schemaPair(
            input = obj(
                "artifactId" to stringField(),
                "chunkId" to stringField(),
                "tenantId" to stringField(),
            ).required("artifactId", "chunkId"),
            output = obj(
                "content" to stringField(),
                "encoding" to enumField("base64", "utf8"),
                "isLast" to booleanField(),
            ).required("content", "encoding", "isLast"),
        ))

        // Job-start tools
        put("schema_reverse_start", jobStart("connectionId"))
        put("schema_compare_start", schemaPair(
            input = obj(
                "sourceUri" to stringField(),
                "targetUri" to stringField(),
            ).required("sourceUri", "targetUri"),
            output = jobIdOut(),
        ))
        put("data_profile_start", jobStart("connectionId"))
        put("data_export_start", schemaPair(
            input = obj(
                "sourceConnectionId" to stringField(),
                "querySpecRef" to stringField(),
            ).required("sourceConnectionId"),
            output = jobIdOut(),
        ))

        // Upload-session tools
        put("artifact_upload_init", schemaPair(
            input = obj(
                "kind" to enumField("SCHEMA", "PROFILE", "DIFF", "DATA_EXPORT", "UPLOAD_INPUT", "OTHER"),
                "expectedSizeBytes" to integerField(),
                "filename" to stringField(),
            ).required("kind"),
            output = obj(
                "sessionId" to stringField(),
                "chunkSizeBytes" to integerField(),
            ).required("sessionId", "chunkSizeBytes"),
        ))
        put("artifact_upload_chunk", schemaPair(
            input = obj(
                "sessionId" to stringField(),
                "chunkIndex" to integerField(),
                "content" to stringField(),
                "encoding" to enumField("base64"),
            ).required("sessionId", "chunkIndex", "content", "encoding"),
            output = obj(
                "accepted" to booleanField(),
            ).required("accepted"),
        ))
        put("artifact_upload_complete", schemaPair(
            input = obj("sessionId" to stringField()).required("sessionId"),
            output = obj(
                "artifactId" to stringField(),
                "sha256" to stringField(),
            ).required("artifactId", "sha256"),
        ))
        put("artifact_upload_abort", schemaPair(
            input = obj("sessionId" to stringField()).required("sessionId"),
            output = obj("aborted" to booleanField()).required("aborted"),
        ))

        // Data-write tools
        put("data_import_start", schemaPair(
            input = obj(
                "targetConnectionId" to stringField(),
                "sourceArtifactId" to stringField(),
            ).required("targetConnectionId", "sourceArtifactId"),
            output = jobIdOut(),
        ))
        put("data_transfer_start", schemaPair(
            input = obj(
                "sourceConnectionId" to stringField(),
                "targetConnectionId" to stringField(),
                "scopeSpecRef" to stringField(),
            ).required("sourceConnectionId", "targetConnectionId"),
            output = jobIdOut(),
        ))

        // Cancel
        put("job_cancel", schemaPair(
            input = obj("jobId" to stringField()).required("jobId"),
            output = obj("cancelled" to booleanField()).required("cancelled"),
        ))

        // AI tools
        put("procedure_transform_plan", schemaPair(
            input = obj(
                "sourceProcedureRef" to stringField(),
                "targetDialect" to stringField(),
            ).required("sourceProcedureRef", "targetDialect"),
            output = obj("planRef" to stringField()).required("planRef"),
        ))
        put("procedure_transform_execute", schemaPair(
            input = obj("planRef" to stringField()).required("planRef"),
            output = obj("artifactId" to stringField()).required("artifactId"),
        ))
        put("testdata_plan", schemaPair(
            input = obj(
                "schemaId" to stringField(),
                "sizeHint" to integerField(),
            ).required("schemaId"),
            output = obj("planRef" to stringField()).required("planRef"),
        ))
        put("testdata_execute", schemaPair(
            input = obj("planRef" to stringField()).required("planRef"),
            output = obj("artifactId" to stringField()).required("artifactId"),
        ))
    }

    // ---------- builders ----------

    private fun schemaPair(input: Map<String, Any>, output: Map<String, Any>): SchemaPair =
        SchemaPair(inputSchema = input, outputSchema = output)

    private fun emptyObject(): Map<String, Any> = mapOf(
        JsonSchemaDialect.SCHEMA_KEYWORD to JsonSchemaDialect.SCHEMA_URI,
        "type" to "object",
        "additionalProperties" to false,
    )

    private fun obj(vararg fields: Pair<String, Map<String, Any>>): SchemaBuilder =
        SchemaBuilder(properties = fields.toMap())

    /**
     * Mutable builder for object-type schemas. Both [required] and
     * [build] are terminal — they return the assembled
     * `Map<String, Any>` directly. Call exactly ONE of them; the
     * builder has no method that takes a `SchemaBuilder` back, so
     * `.required(...).required(...)` is a compile error.
     *
     * Use `.required("a", "b")` when there are required fields,
     * `.build()` otherwise. Keeping the call shape one-line short.
     */
    private class SchemaBuilder(
        private val properties: Map<String, Map<String, Any>>,
    ) {
        fun required(vararg names: String): Map<String, Any> = assemble(names.toList())

        fun build(): Map<String, Any> = assemble(emptyList())

        private fun assemble(required: List<String>): Map<String, Any> = buildMap {
            put(JsonSchemaDialect.SCHEMA_KEYWORD, JsonSchemaDialect.SCHEMA_URI)
            put("type", "object")
            put("additionalProperties", false)
            if (properties.isNotEmpty()) put("properties", properties)
            if (required.isNotEmpty()) put("required", required)
        }
    }

    private fun stringField(): Map<String, Any> = mapOf("type" to "string")
    private fun booleanField(): Map<String, Any> = mapOf("type" to "boolean")
    private fun integerField(): Map<String, Any> = mapOf("type" to "integer", "minimum" to 0)
    private fun objectField(): Map<String, Any> =
        mapOf("type" to "object", "additionalProperties" to true)

    private fun arrayField(itemType: String? = null): Map<String, Any> = if (itemType == null) {
        mapOf("type" to "array")
    } else {
        mapOf("type" to "array", "items" to mapOf("type" to itemType))
    }

    private fun enumField(vararg values: String): Map<String, Any> = mapOf(
        "type" to "string",
        "enum" to values.toList(),
    )

    /** Listing-tools share the same input shape (cursor-based pagination). */
    private fun listInput(itemsField: String): SchemaPair = schemaPair(
        input = obj(
            "tenantId" to stringField(),
            "pageSize" to mapOf("type" to "integer", "minimum" to 1),
            "cursor" to stringField(),
        ).build(),
        output = obj(
            itemsField to arrayField(),
            "nextCursor" to stringField(),
        ).required(itemsField),
    )

    /** Job-start tools share `connectionId` + scope-spec + jobId-out. */
    private fun jobStart(primaryConnectionField: String): SchemaPair = schemaPair(
        input = obj(
            primaryConnectionField to stringField(),
            "tenantId" to stringField(),
            "includes" to arrayField("string"),
            "excludes" to arrayField("string"),
        ).required(primaryConnectionField),
        output = jobIdOut(),
    )

    private fun jobIdOut(): Map<String, Any> =
        obj("jobId" to stringField()).required("jobId")

    /** capabilities_list output is open-ended JSON; output schema reflects that. */
    private fun capabilitiesOutput(): Map<String, Any> = mapOf(
        JsonSchemaDialect.SCHEMA_KEYWORD to JsonSchemaDialect.SCHEMA_URI,
        "type" to "object",
        "additionalProperties" to true,
        "properties" to mapOf(
            "mcpProtocolVersion" to stringField(),
            "dmigrateContractVersion" to stringField(),
            "serverName" to stringField(),
            "tools" to arrayField(),
            "scopeTable" to objectField(),
            "dialects" to arrayField(itemType = "string"),
            "formats" to arrayField(itemType = "string"),
            "limits" to objectField(),
            "executionMeta" to objectField(),
        ),
    )
}
