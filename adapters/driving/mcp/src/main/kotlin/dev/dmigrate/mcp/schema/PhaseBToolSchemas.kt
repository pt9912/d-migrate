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
/**
 * File-level constant so it is initialised before the
 * [PhaseBToolSchemas] object's `SCHEMAS` builder runs (which
 * references it via `jobProgressField()` → `jobProgressNumericValuesField()`
 * → here).
 */
private val JOB_PROGRESS_NUMERIC_KEYS_DATA: List<String> = listOf(
    "processed",
    "total",
    "succeeded",
    "failed",
    "skipped",
    "bytesRead",
    "bytesWritten",
)

internal object PhaseBToolSchemas {
    // Schema-builder primitives (stringField, obj, schemaPair, …)
    // live as top-level functions in `SchemaPrimitives.kt`.
    // List-tool schemas (job_list / artifact_list / schema_list /
    // profile_list / diff_list) live in `PhaseDListToolSchemas` —
    // this object stays focused on Phase-B/C tool registrations
    // and the AP-6.23 D1-D6 building blocks the typed Phase-C
    // tools share.

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
            // AP 6.23: closed shapes — findings use the shared
            // findingItem helper, artifactRef carries the resource-URI
            // pattern, executionMeta is required-keyed. The if/then
            // constraint pins the truncated → artifactRef coupling so
            // a handler that emits truncated=true without an artifact
            // fallback fails JSON-Schema validation.
            output = obj(
                "valid" to booleanField(),
                "summary" to stringField(),
                "findings" to findingArray(),
                "truncated" to booleanField(),
                "artifactRef" to artifactRefField(),
                "executionMeta" to executionMetaField(),
            )
                .withAllOf(truncatedRequiresField("artifactRef"))
                .required("valid", "summary", "findings", "truncated"),
        ))
        put("schema_compare", schemaPair(
            input = obj(
                "left" to schemaSideField(),
                "right" to schemaSideField(),
                "format" to enumField(*FORMAT_NAMES),
            ).required("left", "right"),
            // AP 6.23: findings carry an optional compare-specific
            // details slot ({before?, after?} as scrubbed strings,
            // additionalProperties=false, minProperties=1 so a `{}`
            // is not a valid placeholder); diffArtifactRef uses the
            // shared URI pattern; truncated → diffArtifactRef is
            // pinned by the if/then constraint.
            output = obj(
                "status" to enumField("identical", "different"),
                "summary" to stringField(),
                "findings" to findingArray(detailsSchema = compareDetailsSchema()),
                "truncated" to booleanField(),
                "diffArtifactRef" to artifactRefField(),
                "executionMeta" to executionMetaField(),
            )
                .withAllOf(truncatedRequiresField("diffArtifactRef"))
                .required("status", "summary", "findings", "truncated"),
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
            // AP 6.23: findings use the generator-specific item
            // (base findingItem + optional `hint`). artifactRef
            // carries the URI pattern; ddl is optional (large DDL
            // moves into the artefact); truncated → artifactRef is
            // pinned by the if/then constraint and now covers
            // findings-only overflow as well.
            output = obj(
                "dialect" to stringField(),
                "statementCount" to integerField(),
                "summary" to stringField(),
                "findings" to generatorFindingArray(),
                "truncated" to booleanField(),
                "ddl" to stringField(),
                "artifactRef" to artifactRefField(),
                "executionMeta" to executionMetaField(),
            )
                .withAllOf(truncatedRequiresField("artifactRef"))
                .required("dialect", "statementCount", "summary", "findings", "truncated"),
        ))
        // Phase-D §10.5 + §6.4: typed list-tool schemas live in
        // `PhaseDListToolSchemas`; the registry merges them in
        // through one putAll so the lookup-by-tool-name surface
        // stays unified.
        putAll(PhaseDListToolSchemas.allPairs())

        put("job_status_get", schemaPair(
            input = obj(
                "jobId" to stringField(),
                "resourceUri" to stringField(),
            ).build(),
            // AP 6.23: typed progress + error, allowlist-filtered
            // numericValues, artefact + job URI patterns, closed
            // executionMeta. resourceUri is the job-resource-URI
            // pattern (different from artifactRef); artifacts[] uses
            // the artefact-resource-URI pattern so naked ids cannot
            // leak past the schema validator.
            output = obj(
                "jobId" to stringField(),
                "operation" to stringField(),
                "status" to enumField("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"),
                "terminal" to booleanField(),
                "createdAt" to stringField(),
                "updatedAt" to stringField(),
                "expiresAt" to stringField(),
                "resourceUri" to jobResourceUriField(),
                "artifacts" to mapOf(
                    "type" to "array",
                    "items" to artifactRefField(),
                ),
                "progress" to jobProgressField(),
                "error" to jobErrorField(),
                // Plan §7.6: dieselbe `executionMeta`-Form wie `job_cancel`
                // (Cancel-Felder bei Pending/Ack-Pending/Terminal-Cancel).
                "executionMeta" to executionMetaJobField(),
            ).required(
                "jobId",
                "operation",
                "status",
                "terminal",
                "createdAt",
                "updatedAt",
                "expiresAt",
                "resourceUri",
                "artifacts",
                "executionMeta",
            ),
        ))

        put("artifact_chunk_get", schemaPair(
            input = obj(
                "artifactId" to stringField(),
                // AP D9: `chunkId` stays as Phase-C compat input;
                // `nextChunkCursor` is the Phase-D continuation
                // path. Exactly one of the two MUST be supplied
                // (server-side check); both unset means "first
                // chunk".
                "chunkId" to stringField(),
                "nextChunkCursor" to stringField(),
            ).required("artifactId"),
            output = obj(
                "artifactId" to stringField(),
                "resourceUri" to stringField(),
                "chunkId" to stringField(),
                "offset" to integerField(),
                "lengthBytes" to integerField(),
                "contentType" to stringField(),
                "encoding" to enumField("text", "base64"),
                "text" to stringField(),
                "contentBase64" to stringField(),
                "sha256" to stringField(),
                // AP D9 review: nextChunkUri and nextChunkCursor are
                // wire-explicit `null` on the last chunk per §5.5
                // line 471 ("nextChunkUri MUST be present, null on
                // the last chunk") and Plan-D §10.9. Declared as
                // string|null so a Schema-validating client doesn't
                // reject the terminal response.
                "nextChunkUri" to nullableStringField(),
                "nextChunkCursor" to nullableStringField(),
                "executionMeta" to objectField(),
            ).required(
                "artifactId",
                "resourceUri",
                "chunkId",
                "offset",
                "lengthBytes",
                "contentType",
                "encoding",
                "sha256",
                "nextChunkUri",
                "nextChunkCursor",
                "executionMeta",
            ),
        ))

        // Job-start tools
        put("schema_reverse_start", jobStart("connectionId"))
        put("schema_compare_start", schemaPair(
            input = obj(
                "sourceUri" to stringField(),
                "targetUri" to stringField(),
                "tenantId" to stringField(),
                "idempotencyKey" to stringField(),
                "approvalToken" to stringField(),
            ).required("sourceUri", "targetUri", "idempotencyKey"),
            output = jobStartOut(),
        ))
        put("data_profile_start", jobStart("connectionId"))

        // Upload-session tools
        put("artifact_upload_init", schemaPair(
            input = obj(
                "uploadIntent" to enumField("schema_staging_readonly"),
                "expectedSizeBytes" to integerField(minimum = 1),
                "checksumSha256" to stringField(),
                "filename" to stringField(),
            ).required("uploadIntent", "expectedSizeBytes", "checksumSha256"),
            output = obj(
                "uploadSessionId" to stringField(),
                "uploadSessionTtlSeconds" to integerField(),
                "expectedFirstSegmentIndex" to integerField(minimum = 1),
                "expectedFirstSegmentOffset" to integerField(),
                "executionMeta" to objectField(),
            ).required(
                "uploadSessionId",
                "uploadSessionTtlSeconds",
                "expectedFirstSegmentIndex",
                "expectedFirstSegmentOffset",
            ),
        ))
        put("artifact_upload", schemaPair(
            input = obj(
                "uploadSessionId" to stringField(),
                "segmentIndex" to integerField(minimum = 1),
                "segmentOffset" to integerField(),
                "segmentTotal" to integerField(minimum = 1),
                "isFinalSegment" to booleanField(),
                "segmentSha256" to stringField(),
                "contentBase64" to stringField(),
                "clientRequestId" to stringField(),
            ).required(
                "uploadSessionId",
                "segmentIndex",
                "segmentOffset",
                "segmentTotal",
                "isFinalSegment",
                "segmentSha256",
                "contentBase64",
            ),
            output = obj(
                "uploadSessionId" to stringField(),
                "acceptedSegmentIndex" to integerField(minimum = 1),
                "deduplicated" to booleanField(),
                "bytesReceived" to integerField(),
                "uploadSessionTtlSeconds" to integerField(),
                "uploadSessionState" to enumField("ACTIVE", "COMPLETED"),
                "schemaRef" to stringField(),
                "executionMeta" to objectField(),
            ).required(
                "uploadSessionId",
                "acceptedSegmentIndex",
                "deduplicated",
                "bytesReceived",
                "uploadSessionTtlSeconds",
                "uploadSessionState",
            ),
        ))
        put("artifact_upload_abort", schemaPair(
            input = obj("uploadSessionId" to stringField()).required("uploadSessionId"),
            output = obj(
                "uploadSessionId" to stringField(),
                "uploadSessionState" to enumField("ABORTED"),
                "segmentsDeleted" to integerField(),
                "executionMeta" to objectField(),
            ).required("uploadSessionId", "uploadSessionState", "segmentsDeleted"),
        ))

        // Data-write tools
        // Phase F § 6.1 + § 8.7 (F.7 1/5): Vollstaendiges
        // `data_import_start`-Tool-Schema. Plan-§-6.1-Optionen werden
        // flat unter dem Input gefuehrt (nicht in einem `options`-
        // Wrapper), damit das additionalProperties=false-Gate
        // unbekannte Optionsnamen direkt blockiert. Die fachliche
        // Validierung (table/tables-Exklusivitaet, format/mimeType-
        // Kompatibilitaet, Artefakt-Eignung, chunkSize<=10000 etc.)
        // liegt im Handler, sodass strukturelle vs. semantische
        // Fehler getrennt diagnostizierbar bleiben.
        put("data_import_start", schemaPair(
            input = obj(
                // Plan § 6.1: Pflicht-Identitaet
                "idempotencyKey" to stringField(),
                "targetConnectionRef" to stringField(),
                // Eingabe-Artefakt — `artifactId` oder
                // `sourceArtifactRef`; Handler erzwingt exactly-one.
                "artifactId" to stringField(),
                "sourceArtifactRef" to artifactRefField(),
                // Optional Tabellen-Bindung (Single-File / Bundle).
                "table" to stringField(),
                "tables" to arrayField("string"),
                // Follow-up AP 2: Bundle-/Mehrtabellen-Import.
                // `bundleFormat` ist Pflicht, sobald `tables` gesetzt
                // ist; akzeptierte Werte sind die versionierten
                // Bundle-Format-Marker aus
                // [dev.dmigrate.server.core.upload.bundle.BundleFormat.ALL].
                "bundleFormat" to stringField(),
                // Optional Schema-Preflight + Format-Override.
                "schemaRef" to stringField(),
                "format" to enumField("json", "yaml", "csv"),
                // Plan § 6.1 Import-Optionen.
                "onError" to enumField("abort", "skip", "log"),
                "onConflict" to enumField("abort", "skip", "update"),
                "triggerMode" to enumField("fire", "disable", "strict"),
                "truncate" to booleanField(),
                "disableFkChecks" to booleanField(),
                "reseedSequences" to booleanField(),
                "encoding" to stringField(),
                "csvNoHeader" to booleanField(),
                "csvNullString" to stringField(),
                // Plan § 6.1: chunkSize positive Ganzzahl bis 10000.
                // Schema sichert minimum=1; Handler validiert <=10000
                // (semantic).
                "chunkSize" to integerField(minimum = 1),
                // Plan § 5.5: Caller liefert Token nach
                // `RequiresApproval` nach.
                "approvalToken" to stringField(),
            ).required("idempotencyKey", "targetConnectionRef"),
            output = jobStartOut(),
        ))
        // Phase F § 6.2 + § 8.8 (F.8 1/4): Vollstaendiges
        // `data_transfer_start`-Tool-Schema. Plan-§-6.2-Optionen werden
        // flat unter dem Input gefuehrt (analog zu data_import_start
        // aus F.7), damit `additionalProperties=false` unbekannte
        // Optionsnamen direkt am Schema-Gate blockiert.
        put("data_transfer_start", schemaPair(
            input = obj(
                // Plan § 6.2: Pflicht-Identitaet
                "idempotencyKey" to stringField(),
                "sourceConnectionRef" to stringField(),
                "targetConnectionRef" to stringField(),
                // Plan § 6.2 Transfer-Optionen
                "tables" to arrayField("string"),
                "filter" to stringField(),
                "sinceColumn" to stringField(),
                "since" to stringField(),
                "onConflict" to enumField("abort", "skip", "update"),
                "triggerMode" to enumField("fire", "disable", "strict"),
                "truncate" to booleanField(),
                // chunkSize: positive Ganzzahl bis 10000 (Plan § 6.2).
                // Schema sichert minimum=1; Handler validiert <=10000.
                "chunkSize" to integerField(minimum = 1),
                // Plan § 5.5: Caller liefert Token nach
                // `RequiresApproval` nach.
                "approvalToken" to stringField(),
            ).required("idempotencyKey", "sourceConnectionRef", "targetConnectionRef"),
            output = jobStartOut(),
        ))

        // Cancel — Plan §5.6 / §7.6: Eingabe `jobId` ODER tenant-scoped
        // `resourceUri` (server-side check stellt "exactly-one" sicher);
        // optional `reason`. Output liefert aktuellen Jobstatus +
        // `executionMeta` mit Cancel-Feldern, KEIN `cancelled: boolean`
        // mehr (Phase-B-Golden-Migration). `executionMeta` teilt sich die
        // Form mit `job_status_get`, damit Pending-/Ack-Pending-/Terminal-
        // Cancel einheitlich projiziert werden (Plan §7.6).
        put("job_cancel", schemaPair(
            input = obj(
                "jobId" to stringField(),
                "resourceUri" to jobResourceUriField(),
                "reason" to stringField(),
            ).build(),
            output = obj(
                "jobId" to stringField(),
                "operation" to stringField(),
                "status" to enumField("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"),
                "terminal" to booleanField(),
                "resourceUri" to jobResourceUriField(),
                "executionMeta" to executionMetaJobField(),
            ).required("jobId", "operation", "status", "terminal", "resourceUri", "executionMeta"),
        ))

        // Phase G § 5.4 + § 8.5 (G.5): vollstaendiges
        // `procedure_transform_plan`-Schema. Plan-§-5.4-Eingaben
        // werden flat unter dem Input gefuehrt (nicht in einem
        // Wrapper), damit `additionalProperties=false` unbekannte
        // Felder direkt am Schema-Gate blockiert. Die fachliche
        // Validierung (genau eine Source-Variante, schemaRef +
        // procedureName paarweise, Provider-Resolution) liegt im
        // Handler, sodass strukturelle vs. semantische Fehler
        // getrennt diagnostizierbar bleiben.
        put("procedure_transform_plan", schemaPair(
            input = obj(
                // Plan §5.4 Pflicht-Identitaet:
                "approvalKey" to stringField(),
                // Plan §5.5: Caller liefert Token nach
                // `RequiresApproval` nach.
                "approvalToken" to stringField(),
                // Plan §5.4: genau EINE Source-Variante. Schema
                // listet alle drei; Handler erzwingt exactly-one.
                "procedureRef" to stringField(),
                "artifactRef" to artifactRefField(),
                "schemaRef" to stringField(),
                "procedureName" to stringField(),
                // Plan §5.4 Pflichtfeld:
                "targetDialect" to enumField(*DIALECT_NAMES),
                // Plan §5.4 Optionsfelder:
                "rules" to objectField(),
                "profileRef" to stringField(),
                "diffRef" to stringField(),
                // Optionale Provider-Auswahl (Plan §5.4 + §5.2):
                "providerId" to stringField(),
                "model" to stringField(),
            ).required("approvalKey", "targetDialect"),
            output = obj(
                "summary" to stringField(),
                "findings" to findingArray(),
                // Plan §5.4 Output: `planRef` ODER `planArtifactId`.
                // Schema listet beide; Handler garantiert min. einen.
                "planRef" to artifactRefField(),
                "planArtifactId" to stringField(),
                "planResourceUri" to artifactRefField(),
                "providerMeta" to providerMetaField(),
                "executionMeta" to executionMetaField(),
            ).required("summary", "findings", "providerMeta", "executionMeta"),
        ))

        // Phase G § 5.5 + § 8.5 (G.5): vollstaendiges
        // `procedure_transform_execute`-Schema. Plan §5.5 Z. 770:
        // `planRef` ODER `planArtifactId` referenziert das
        // freigegebene Plan-Artefakt aus `procedure_transform_plan`;
        // Schema listet beide, Handler erzwingt exactly-one.
        // Source-Refs sind hier bewusst NICHT — Plan §5.5 Z. 794-799:
        // Source-Refs werden ausschliesslich aus der Provenance des
        // Plan-Artefakts validiert; der Execute-Payload enthaelt
        // keine eigenen Source-Refs.
        put("procedure_transform_execute", schemaPair(
            input = obj(
                "approvalKey" to stringField(),
                "approvalToken" to stringField(),
                "planRef" to artifactRefField(),
                "planArtifactId" to stringField(),
                "targetDialect" to enumField(*DIALECT_NAMES),
                "executionOptions" to objectField(),
                "providerId" to stringField(),
                "model" to stringField(),
            ).required("approvalKey", "targetDialect"),
            output = obj(
                "summary" to stringField(),
                "findings" to findingArray(),
                "targetArtifactId" to stringField(),
                "targetResourceUri" to artifactRefField(),
                "providerMeta" to providerMetaField(),
                "executionMeta" to executionMetaField(),
            ).required(
                "summary", "findings",
                "targetArtifactId", "targetResourceUri",
                "providerMeta", "executionMeta",
            ),
        ))

        // Phase G § 5.6 + § 8.5 (G.5): vollstaendiges
        // `testdata_plan`-Schema. Plan §5.6 Pflichtfelder:
        // `approvalKey`, `schemaRef`, `targetDialect`. Optional:
        // `profileRef`, `rules`, Provider-Auswahl.
        put("testdata_plan", schemaPair(
            input = obj(
                "approvalKey" to stringField(),
                "approvalToken" to stringField(),
                "schemaRef" to stringField(),
                "profileRef" to stringField(),
                "rules" to objectField(),
                "targetDialect" to enumField(*DIALECT_NAMES),
                "providerId" to stringField(),
                "model" to stringField(),
            ).required("approvalKey", "schemaRef", "targetDialect"),
            output = obj(
                "summary" to stringField(),
                "findings" to findingArray(),
                "testdataPlanArtifactId" to stringField(),
                "testdataPlanResourceUri" to artifactRefField(),
                "providerMeta" to providerMetaField(),
                "executionMeta" to executionMetaField(),
            ).required(
                "summary", "findings",
                "providerMeta", "executionMeta",
            ),
        ))

        // Follow-up AP 3 (Plan §5): `testdata_execute` ist jetzt
        // produktiv. Das Tool konsumiert ein freigegebenes
        // testdata-plan-Artefakt und veröffentlicht ein importierbares
        // Datenartefakt mit synthetischer ArtifactUploadMetadata
        // (Pfad-A); kein direkter Datenbank-Write.
        put("testdata_execute", schemaPair(
            input = obj(
                "approvalKey" to stringField(),
                "targetDialect" to stringField(),
                "approvalToken" to stringField(),
                "planRef" to stringField(),
                "planArtifactId" to stringField(),
                "outputFormat" to enumField("csv", "json", "yaml"),
                "bundleFormat" to stringField(),
                "targetTable" to stringField(),
                "tables" to arrayField("string"),
                "providerId" to stringField(),
                "model" to stringField(),
                "rowLimit" to integerField(minimum = 1),
                "seed" to stringField(),
            ).required("approvalKey", "targetDialect"),
            output = obj(
                "summary" to stringField(),
                "testdataArtifactId" to stringField(),
                "testdataResourceUri" to stringField(),
                "providerMeta" to objectField(),
                "executionMeta" to objectField(),
            ).required("testdataArtifactId", "testdataResourceUri"),
        ))
    }

    // ---------- AP-6.23 typed-tool building blocks ----------

    /**
     * `schema_compare` operand shape: only `schemaRef` is accepted in
     * Phase C (§5.3 + §6.6). `connectionRef` and inline `schema` are
     * runtime-rejected — the JSON-Schema deliberately does NOT list
     * them so a JSON-Schema-validating client gets the same answer.
     */
    private fun schemaSideField(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf("schemaRef" to stringField()),
        "required" to listOf("schemaRef"),
    )

    /**
     * AP 6.23: `if truncated=true then required [refField]`.
     * Used by `schema_validate` (artifactRef), `schema_compare`
     * (diffArtifactRef), and `schema_generate` (artifactRef) to make
     * the truncated-output / artefact-fallback contract machine-
     * checkable in the JSON-Schema layer instead of relying on
     * runtime tests alone.
     *
     * Review N3: the `required: ["truncated"]` inside the `if` block
     * is defensive — JSON-Schema 2020-12 evaluates `properties.X.const=Y`
     * as vacuously true when `X` is absent. Adding `required` makes
     * the predicate strict and prevents a future schema where
     * `truncated` becomes optional from silently disabling the
     * constraint. All four AP-6.23 tools mark `truncated` as required
     * in the outer schema, so this is redundant today but cheap
     * insurance.
     */
    internal fun truncatedRequiresField(refField: String): Map<String, Any> = mapOf(
        "if" to mapOf(
            "properties" to mapOf("truncated" to mapOf("const" to true)),
            "required" to listOf("truncated"),
        ),
        "then" to mapOf("required" to listOf(refField)),
    )

    /**
     * AP 6.23: `schema_compare`-specific finding details. Either
     * `before`, `after`, or both are present — each is a non-empty
     * scrubbed string (pattern `\S` rejects blanks / pure whitespace).
     * `additionalProperties=false` plus `minProperties=1` means an
     * empty `details: {}` placeholder is invalid; additive / removal
     * findings without before/after omit `details` entirely.
     */
    internal fun compareDetailsSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "minProperties" to 1,
        "properties" to mapOf(
            "before" to mapOf("type" to "string", "pattern" to "\\S"),
            "after" to mapOf("type" to "string", "pattern" to "\\S"),
        ),
    )

    /**
     * AP 6.23: `schema_generate`-specific finding item — the common
     * [findingItem] base plus the legacy top-level `hint: string`
     * (kept here because the runtime emits it that way; a future
     * `details.hint` migration would be a separate wire-contract
     * change). `additionalProperties=false` is preserved.
     */
    internal fun generatorFindingItem(): Map<String, Any> {
        val baseProps = findingItem()["properties"] as? Map<*, *>
            ?: error("findingItem properties must be an object schema")
        return mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to (baseProps + ("hint" to stringField())),
            "required" to listOf("severity", "code", "path", "message"),
        )
    }

    /** Array of [generatorFindingItem]s. */
    internal fun generatorFindingArray(): Map<String, Any> = mapOf(
        "type" to "array",
        "items" to generatorFindingItem(),
    )

    /**
     * AP 6.23: allowlist for `JobProgress.numericValues` keys. The
     * underlying `Map<String, Long>` stays free internally; the
     * handler projects through this allowlist before serialisation
     * and the schema enforces `additionalProperties=false` so an
     * unknown key fails wire validation.
     *
     * Backed by a file-level [JOB_PROGRESS_NUMERIC_KEYS_DATA] so the
     * value is initialised before [SCHEMAS] runs (`jobProgressField`
     * is called inside the SCHEMAS init block — referencing a
     * not-yet-initialised member would NPE).
     */
    val JOB_PROGRESS_NUMERIC_KEYS: List<String> get() = JOB_PROGRESS_NUMERIC_KEYS_DATA

    internal fun jobProgressField(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf(
            "phase" to stringField(),
            "numericValues" to jobProgressNumericValuesField(),
        ),
        "required" to listOf("phase", "numericValues"),
    )

    private fun jobProgressNumericValuesField(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to JOB_PROGRESS_NUMERIC_KEYS.associateWith {
            mapOf("type" to "integer")
        },
    )

    internal fun jobErrorField(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf(
            "code" to stringField(),
            "message" to stringField(),
            "exitCode" to mapOf("type" to "integer"),
        ),
        "required" to listOf("code", "message"),
    )

    // ──────────────────────────────────────────────────────────────
    // AP 6.23: shared output schema building blocks. The four
    // affected tools (schema_validate, schema_compare, schema_generate,
    // job_status_get) get refactored onto these helpers in D3-D6 so
    // the JSON-Schema and the runtime wire stay in lockstep.
    // Field-primitives (stringField/integerField/etc.) and the
    // shared `artifactRef` pattern live in `SchemaPrimitives.kt`.
    // ──────────────────────────────────────────────────────────────

    /**
     * Closed-shape `executionMeta` object — required `requestId`, no
     * additional properties (so a future debug field cannot land
     * secrets in tool responses by accident).
     */
    internal fun executionMetaField(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf("requestId" to stringField()),
        "required" to listOf("requestId"),
    )

    /**
     * Job-aware `executionMeta` object per Plan §7.6 / §5.6 — adds
     * the durable Cancel-Felder, die `job_status_get` und `job_cancel`
     * EINHEITLICH projizieren muessen. `requestId` bleibt das einzige
     * Pflichtfeld; Cancel-Felder werden nur bei aktivem oder
     * angefordertem Cancel gesetzt. Das Schema bleibt geschlossen
     * (`additionalProperties=false`), damit kein Debug-Feld Secrets
     * leakt.
     *
     * Felder gemaess Plan §7.6 line 1110-1115:
     * - `cancelRequested`: Cancel ist durable angefordert.
     * - `cancelAckPending`: Worker-Ack noch ausstehend (mit `retryAfter`).
     * - `cancelRequestedAt`: Zeitpunkt der durable angeforderten Cancel.
     * - `cancelRequestedBy`: Principal, der den Cancel angefordert hat.
     * - `cancelRequestedReason`: scrubbed Reason-Projektion.
     * - `cancelSignalSource`: Quelle (job-cancel, runner-timeout, …).
     * - `retryAfter`: Sekunden bis zum naechsten `job_cancel`-Retry.
     */
    internal fun executionMetaJobField(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf(
            "requestId" to stringField(),
            "cancelRequested" to booleanField(),
            "cancelAckPending" to booleanField(),
            "cancelRequestedAt" to stringField(),
            "cancelRequestedBy" to stringField(),
            "cancelRequestedReason" to stringField(),
            "cancelSignalSource" to stringField(),
            "retryAfter" to integerField(),
        ),
        "required" to listOf("requestId"),
    )

    /**
     * Common finding item: `severity` enum (uses [SchemaFindingSeverity]
     * wire constants), `code`/`path`/`message` strings, optional
     * `details` slot governed by [detailsSchema] (e.g. compare-specific
     * `before`/`after`). `additionalProperties=false` so the wire
     * shape is closed; tools that need extra keys (e.g.
     * `schema_generate`'s `hint`) get a tool-specific helper that
     * widens the property set explicitly.
     */
    internal fun findingItem(detailsSchema: Map<String, Any>? = null): Map<String, Any> {
        val properties = buildMap<String, Map<String, Any>> {
            put(
                "severity",
                enumField(
                    SchemaFindingSeverity.ERROR,
                    SchemaFindingSeverity.WARNING,
                    SchemaFindingSeverity.INFO,
                ),
            )
            put("code", stringField())
            put("path", stringField())
            put("message", stringField())
            if (detailsSchema != null) put("details", detailsSchema)
        }
        return mapOf(
            "type" to "object",
            "additionalProperties" to false,
            "properties" to properties,
            "required" to listOf("severity", "code", "path", "message"),
        )
    }

    /** Array of [findingItem]s. */
    internal fun findingArray(detailsSchema: Map<String, Any>? = null): Map<String, Any> = mapOf(
        "type" to "array",
        "items" to findingItem(detailsSchema),
    )

    /**
     * Phase G § 5.1 (G.5): closed-shape `providerMeta`-Objekt für
     * KI-nahe Tool-Outputs. `providerName` und `model` sind Pflicht
     * (sonst wäre keine Provenance möglich); `modelVersion` und
     * `requestId` sind optional, weil lokale Provider keine
     * Versionierung/Korrelation liefern müssen. **Enthält bewusst
     * keinen Endpoint, keinen `secretRef` und keine API-Keys**
     * (Plan §4.8 + §5.2 Z. 611-612: Secrets nie in Wire-Antworten).
     */
    internal fun providerMetaField(): Map<String, Any> = mapOf(
        "type" to "object",
        "additionalProperties" to false,
        "properties" to mapOf(
            "providerName" to stringField(),
            "model" to stringField(),
            "modelVersion" to nullableStringField(),
            "requestId" to nullableStringField(),
        ),
        "required" to listOf("providerName", "model"),
    )

    // arrayField/enumField extracted to SchemaPrimitives.kt;
    // list-tool schemas + their items extracted to
    // PhaseDListToolSchemas.kt (Plan-D §10.5/§6.4).

    /**
     * Phase-E §7.6 Job-Start-Tools (`schema_reverse_start`,
     * `data_profile_start`): teilen `connectionId` + scope-spec, plus die
     * Phase-E-Pflichtfelder `idempotencyKey` (Plan §7.6 "idempotencyKey
     * als Pflichtfeld erzwingen") und das optionale `approvalToken`
     * (Plan §5.5: Caller liefert Token nach `RequiresApproval` nach).
     */
    private fun jobStart(primaryConnectionField: String): SchemaPair = schemaPair(
        input = obj(
            primaryConnectionField to stringField(),
            "tenantId" to stringField(),
            "idempotencyKey" to stringField(),
            "approvalToken" to stringField(),
            "includes" to arrayField("string"),
            "excludes" to arrayField("string"),
        ).required(primaryConnectionField, "idempotencyKey"),
        output = jobStartOut(),
    )

    /**
     * Phase-E §7.6 Output-Schema fuer Start-Tools: `jobId` + `resourceUri`
     * + (job-aware) `executionMeta`. Wird von `schema_reverse_start`,
     * `data_profile_start` und `schema_compare_start` geteilt.
     */
    private fun jobStartOut(): Map<String, Any> =
        obj(
            "jobId" to stringField(),
            "resourceUri" to jobResourceUriField(),
            "executionMeta" to executionMetaJobField(),
        ).required("jobId", "resourceUri", "executionMeta")

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
