package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import dev.dmigrate.mcp.registry.JsonArgs.optEnum
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.application.job.JobStartOrchestrator
import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.job.RefField
import dev.dmigrate.server.core.artifact.ArtifactKind
import dev.dmigrate.server.core.artifact.ArtifactRecord
import dev.dmigrate.server.core.artifact.ArtifactUploadMetadata
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.ArtifactContentStore
import dev.dmigrate.server.ports.ConnectionReferenceStore
import dev.dmigrate.server.ports.SchemaStore
import java.time.Clock
import java.util.Locale

/**
 * Phase F § 6.1 + § 8.7 (F.7 2/5) — `data_import_start`-Handler.
 *
 * Pre-Idempotency-Validation:
 *
 * - exactly-one Quelle: `artifactId` ODER `sourceArtifactRef` (Plan
 *   § 6.1: "artifactId oder Artefakt-resourceUri").
 * - `chunkSize`-Obergrenze (`<=10000`, Plan § 6.1).
 * - `targetConnectionRef` als RefField mit `ResourceKind.CONNECTIONS` —
 *   der bestehende [dev.dmigrate.server.application.job.JobStartInputValidator]
 *   weist freie JDBC-URLs, ungueltige URI-Syntax und Tenant-Mismatch
 *   ab (Plan § 7.6 + § 8.7 "strukturelle Validation vor Idempotency
 *   ohne Store-Write").
 *
 * Phase-E Job-Pipeline:
 *
 * - [JobStartOrchestrator] uebernimmt Idempotency-Reservierung,
 *   Policy-Decision, Quota-Reservierung, durable Job-Anlage; der
 *   Handler liefert nur das Tool-Mapping.
 *
 * Der Handler prueft die Artefakt-Eignung (`kind=UPLOAD_INPUT`),
 * `format`/MIME-Kompatibilitaet, Tabellen-Topologie sowie
 * Connection-/Schema-Refs vor Idempotency ohne Store-Write. Der Runner
 * materialisiert erst spaeter Secrets und fuehrt das JDBC-I/O aus.
 */
internal class DataImportStartHandler(
    private val orchestrator: JobStartOrchestrator,
    private val artifactStore: ArtifactStore,
    private val artifactContentStore: ArtifactContentStore,
    private val connectionStore: ConnectionReferenceStore,
    private val schemaStore: SchemaStore,
    private val clock: Clock,
    private val jobRetentionSeconds: Long = DEFAULT_JOB_RETENTION_SECONDS,
) : ToolHandler {

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = JsonArgs.requireObject(context.arguments)
        val idempotencyKey = args.optString("idempotencyKey")
        val approvalToken = args.optString("approvalToken")
        val targetConnectionRef = args.requireString("targetConnectionRef")
        val artifactSource = parseArtifactSource(args)
        validateChunkSize(args)
        validateTableTopology(args)
        validateOptionEnums(args)
        // Phase F § 6.1 (F.7 3/5): Artefakt-Eignungsmatrix.
        // Lookup vor Idempotency, damit "unbekanntes Artefakt" und
        // "falsche Kind/Intent" deterministisch ohne Job-/SyncEffect-
        // Reservierung gemeldet werden.
        val tenantId = context.principal.effectiveTenantId
        val record = resolveArtifact(artifactSource, tenantId)
        val metadata = validateArtifactEligibility(record)
        validateArtifactContent(record)
        val effectiveFormat = validateFormatCompatibility(args, metadata)
        validateTargetTable(args, metadata)
        // Phase F § 8.7 (F.7 4/5): Connection-/Schema-Ref-Resolution
        // VOR der Idempotency-Reservierung. ConnectionReferenceStore
        // liefert nur secret-frei (kein JDBC-URL-Materialise) — der
        // Resolver-Stack (CLI/Runner) zieht Secrets erst beim
        // Job-Run aus dem ConnectionSecretResolver. Plan § 8.7
        // wortlaeufig: "Policy nur mit secret-freien Metadaten".
        resolveConnectionRef(targetConnectionRef, tenantId)
        val schemaRef = args.optString("schemaRef")
        if (!schemaRef.isNullOrBlank()) {
            resolveSchemaRef(schemaRef, tenantId)
        }

        val now = clock.instant()

        val refs = buildList {
            add(
                RefField(
                    name = "targetConnectionRef",
                    value = targetConnectionRef,
                    expectedKind = ResourceKind.CONNECTIONS,
                ),
            )
            // Wenn der Caller den Artefakt-URI mitgibt, nimmt der
            // [JobStartInputValidator] die tenant-scoped-Pruefung
            // auch fuer ihn mit. `artifactId` (rein opaque) wird im
            // F.7-(3/5)-Step gegen den Store aufgeloest.
            if (artifactSource is ArtifactSource.Ref) {
                add(
                    RefField(
                        name = "sourceArtifactRef",
                        value = artifactSource.value,
                        expectedKind = ResourceKind.ARTIFACTS,
                    ),
                )
            }
        }

        val request = JobStartRequest(
            toolName = TOOL_NAME,
            tenantId = tenantId,
            callerId = context.principal.principalId,
            idempotencyKey = idempotencyKey,
            approvalToken = approvalToken,
            payload = enrichPayloadForFingerprint(args, record, metadata, effectiveFormat),
            refs = refs,
            now = now,
            auditFields = context.auditFields,
            principalContext = context.principal,
            jobBuilder = { jobId, createdAt ->
                JobRecord(
                    managedJob = ManagedJob(
                        jobId = jobId,
                        operation = OPERATION,
                        status = JobStatus.QUEUED,
                        createdAt = createdAt,
                        updatedAt = createdAt,
                        expiresAt = createdAt.plusSeconds(jobRetentionSeconds),
                        createdBy = context.principal.principalId.value,
                    ),
                    tenantId = tenantId,
                    ownerPrincipalId = context.principal.principalId,
                    visibility = JobVisibility.OWNER,
                    resourceUri = ServerResourceUri(
                        tenantId = tenantId,
                        kind = ResourceKind.JOBS,
                        id = jobId,
                    ),
                )
            },
        )

        val outcome = orchestrator.start(request)
        return JobStartHandlerSupport.toToolCallOutcome(outcome, tenantId, context.requestId)
    }

    /**
     * Plan § 6.1: `artifactId` ODER `sourceArtifactRef` Pflicht;
     * beide gleichzeitig waeren mehrdeutig (Caller koennte einen
     * Mismatch zwischen den beiden vortaeuschen). Genau eines ist
     * gueltig.
     */
    private fun parseArtifactSource(args: JsonObject): ArtifactSource {
        val artifactId = args.optString("artifactId")
        val sourceRef = args.optString("sourceArtifactRef")
        return when {
            artifactId.isNullOrBlank() && sourceRef.isNullOrBlank() ->
                throw ValidationErrorException(
                    listOf(ValidationViolation(
                        "artifactId",
                        "exactly one of 'artifactId' or 'sourceArtifactRef' is required",
                    )),
                )
            !artifactId.isNullOrBlank() && !sourceRef.isNullOrBlank() ->
                throw ValidationErrorException(
                    listOf(ValidationViolation(
                        "artifactId",
                        "'artifactId' and 'sourceArtifactRef' are mutually exclusive",
                    )),
                )
            !artifactId.isNullOrBlank() -> ArtifactSource.Id(artifactId)
            else -> ArtifactSource.Ref(sourceRef!!)
        }
    }

    /**
     * Plan § 6.1: `chunkSize` ist eine "positive Ganzzahl bis 10000".
     * Schema sichert `minimum=1`; die Obergrenze liegt im Handler,
     * weil JSON-Schema-`maximum` an manchen Wire-Schichten nicht
     * konsequent enforced wird (defense in depth).
     */
    private fun validateChunkSize(args: JsonObject) {
        val element = args.get("chunkSize")?.takeUnless { it.isJsonNull } ?: return
        val primitive = element as? com.google.gson.JsonPrimitive
        if (primitive == null || !primitive.isNumber) {
            throw ValidationErrorException(
                listOf(ValidationViolation("chunkSize", "must be an integer")),
            )
        }
        val value = primitive.asLong
        if (value < 1L || value > MAX_CHUNK_SIZE) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "chunkSize",
                    "must be in [1, $MAX_CHUNK_SIZE]",
                )),
            )
        }
    }

    private fun validateOptionEnums(args: JsonObject) {
        args.optEnum("onError", IMPORT_ON_ERROR_VALUES)
        args.optEnum("onConflict", IMPORT_ON_CONFLICT_VALUES)
        args.optEnum("triggerMode", IMPORT_TRIGGER_MODE_VALUES)
    }

    /**
     * Phase F § 6.1 (F.7 3/5) + Follow-up AP 2 — table/tables-Topologie.
     *
     * - Beide gleichzeitig: VALIDATION_ERROR (mehrdeutig).
     * - Follow-up AP 2: `tables` ist erlaubt, wenn `bundleFormat`
     *   gesetzt ist und einen versionierten Wert aus
     *   [BundleFormat.ALL] trägt. Ohne `bundleFormat` bleibt `tables`
     *   `VALIDATION_ERROR`.
     * - `tables` als leeres Array oder mit leeren Strings:
     *   VALIDATION_ERROR (Plan: "leere oder syntaktisch ungueltige
     *   tables -> VALIDATION_ERROR").
     */
    private fun validateTableTopology(args: JsonObject) {
        val table = args.optString("table")
        val tablesElement = args.get("tables")?.takeUnless { it.isJsonNull }
        val bundleFormat = args.optString("bundleFormat")

        validateTableExclusivity(table, tablesElement, bundleFormat)
        if (tablesElement != null) {
            validateTablesArrayShape(tablesElement)
            validateBundleFormatPresence(bundleFormat)
        }
    }

    private fun validateTableExclusivity(
        table: String?,
        tablesElement: com.google.gson.JsonElement?,
        bundleFormat: String?,
    ) {
        val violation = when {
            !table.isNullOrBlank() && tablesElement != null -> ValidationViolation(
                "table", "'table' and 'tables' are mutually exclusive",
            )
            !table.isNullOrBlank() && !bundleFormat.isNullOrBlank() -> ValidationViolation(
                "bundleFormat",
                "must not be combined with 'table' — use 'tables' for bundle imports",
            )
            else -> null
        }
        if (violation != null) throw ValidationErrorException(listOf(violation))
    }

    private fun validateTablesArrayShape(tablesElement: com.google.gson.JsonElement) {
        val violation = when {
            !tablesElement.isJsonArray -> ValidationViolation("tables", "must be an array of strings")
            tablesElement.asJsonArray.isEmpty -> ValidationViolation("tables", "must not be empty")
            tablesElement.asJsonArray.any { entry ->
                val isString = entry.isJsonPrimitive && entry.asJsonPrimitive.isString
                !isString || entry.asString.isBlank()
            } -> ValidationViolation("tables", "items must be non-blank strings")
            else -> null
        }
        if (violation != null) throw ValidationErrorException(listOf(violation))
        val items = tablesElement.asJsonArray.map { it.asString }
        if (items.distinct().size != items.size) {
            throw ValidationErrorException(
                listOf(ValidationViolation("tables", "must not contain duplicates")),
            )
        }
    }

    private fun validateBundleFormatPresence(bundleFormat: String?) {
        val violation = when {
            bundleFormat.isNullOrBlank() -> ValidationViolation(
                "bundleFormat", "is required when 'tables' is set",
            )
            bundleFormat !in dev.dmigrate.server.core.upload.bundle.BundleFormat.ALL ->
                ValidationViolation(
                    "bundleFormat",
                    "must be one of " +
                        dev.dmigrate.server.core.upload.bundle.BundleFormat.ALL.joinToString(","),
                )
            else -> null
        }
        if (violation != null) throw ValidationErrorException(listOf(violation))
    }

    /**
     * Phase F § 6.1 (F.7 3/5): Artefakt-Lookup.
     *
     * - `artifactId`: direkter Lookup im tenant-scoped [ArtifactStore].
     * - `sourceArtifactRef`: Resource-URI parsen, tenant-Match
     *   pruefen, dann Lookup ueber den extrahierten artifactId.
     *
     * Plan § 6.1 wortlaeufig: "unbekannte Artefakte liefern
     * `RESOURCE_NOT_FOUND`". Cross-Tenant-Refs werden bereits durch
     * `ServerResourceUri`-Parsing + Tenant-Match abgewiesen — der
     * Caller-Tenant ist hier autoritativ (kein Oracle ueber fremde
     * Tenants).
     */
    private fun resolveArtifact(source: ArtifactSource, tenantId: TenantId): ArtifactRecord {
        val artifactId = when (source) {
            is ArtifactSource.Id -> source.value
            is ArtifactSource.Ref -> extractArtifactIdFromRef(source.value, tenantId)
        }
        val record = artifactStore.findById(tenantId, artifactId)
            ?: throw ResourceNotFoundException(
                ServerResourceUri(tenantId, ResourceKind.ARTIFACTS, artifactId),
            )
        return record
    }

    private fun extractArtifactIdFromRef(refValue: String, tenantId: TenantId): String {
        val parsed = ServerResourceUri.parse(refValue)
        return when (parsed) {
            is ResourceUriParseResult.Invalid ->
                throw ValidationErrorException(
                    listOf(ValidationViolation(
                        "sourceArtifactRef",
                        "invalid resource URI: ${parsed.reason}",
                    )),
                )
            is ResourceUriParseResult.Valid -> {
                if (parsed.uri.kind != ResourceKind.ARTIFACTS) {
                    throw ValidationErrorException(
                        listOf(ValidationViolation(
                            "sourceArtifactRef",
                            "expected artifacts, got ${parsed.uri.kind.pathSegment}",
                        )),
                    )
                }
                if (parsed.uri.tenantId != tenantId) {
                    throw ValidationErrorException(
                        listOf(ValidationViolation(
                            "sourceArtifactRef",
                            "tenant prefix mismatch: caller is ${tenantId.value}",
                        )),
                    )
                }
                parsed.uri.id
            }
        }
    }

    /**
     * Phase F § 6.1 (F.7 3/5): Artefakt-Eignungsmatrix:
     *
     * - `kind != UPLOAD_INPUT` -> VALIDATION_ERROR (Plan: "Core-Kind
     *   ausser UPLOAD_INPUT als Import-Artefakt -> VALIDATION_ERROR").
     *   Insbesondere `SCHEMA` (read-only Schema-Staging) ist hart
     *   abzulehnen.
     * - Visibility-Check ist bewusst nicht hier — der Tenant-Scope
     *   ist bereits ueber den findById-Lookup erzwungen.
     *
     * `format`/`mimeType`-Kompatibilitaet wird anschliessend gegen die
     * persistenten [ManagedArtifact.contentType]-Metadaten geprueft.
     */
    private fun validateArtifactEligibility(record: ArtifactRecord): ArtifactUploadMetadata {
        if (record.kind != ArtifactKind.UPLOAD_INPUT) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "artifactId",
                    "import requires artifactKind=UPLOAD_INPUT, got ${record.kind.name} " +
                        "(read-only schema-staging artefacts cannot be imported)",
                )),
            )
        }
        val metadata = record.uploadMetadata
            ?: throw ValidationErrorException(
                listOf(ValidationViolation(
                    "artifactId",
                    "import requires persistent uploadMetadata from uploadIntent=job_input",
                )),
            )
        if (metadata.uploadIntent != ArtifactUploadInitHandler.INTENT_JOB_INPUT) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "artifactId",
                    "import requires uploadIntent=job_input, got ${metadata.uploadIntent}",
                )),
            )
        }
        when (metadata.wireArtifactKind) {
            "seed-data", "generic", ArtifactUploadInitHandler.WIRE_KIND_SEED_DATA_BUNDLE -> Unit
            else -> throw ValidationErrorException(
                listOf(ValidationViolation(
                    "artifactId",
                    "wireArtifactKind=${metadata.wireArtifactKind} is not importable",
                )),
            )
        }
        return metadata
    }

    private fun validateArtifactContent(record: ArtifactRecord) {
        if (!artifactContentStore.exists(record.managedArtifact.artifactId)) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "artifactId",
                    "artifact bytes are missing for persistent uploadMetadata",
                )),
            )
        }
    }

    private fun validateFormatCompatibility(args: JsonObject, metadata: ArtifactUploadMetadata): String {
        // Follow-up AP 2: Bundle-Imports tragen ihr Daten-Format im
        // Manifest, nicht im MIME-Type. Der Bundle-Marker spezifiziert
        // ZIP-Container; das `format`-Wire-Argument wird ignoriert
        // (Caller liefert ggf. den per-Bundle-Format-Hint, der vom
        // Runner aus dem Manifest verifiziert wird).
        if (metadata.wireArtifactKind == ArtifactUploadInitHandler.WIRE_KIND_SEED_DATA_BUNDLE) {
            return metadata.bundleFormat ?: dev.dmigrate.server.core.upload.bundle.BundleFormat.SEED_BUNDLE_V1_ZIP
        }
        val explicitFormat = args.optString("format")?.lowercase(Locale.US)
        if (metadata.wireArtifactKind == "generic" && explicitFormat == null) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "format",
                    "is required for wireArtifactKind=generic",
                )),
            )
        }
        val inferredFormat = metadata.format ?: inferFormat(metadata.contentType)
        val format = explicitFormat ?: inferredFormat
            ?: throw ValidationErrorException(
                listOf(ValidationViolation(
                    "format",
                    "is required because artifact mimeType '${metadata.contentType}' is ambiguous",
                )),
            )
        val mimeType = metadata.contentType.lowercase(Locale.US).substringBefore(";").trim()
        val compatible = when (format) {
            "csv" -> mimeType in CSV_MIME_TYPES
            "json" -> mimeType in JSON_MIME_TYPES
            "yaml" -> mimeType in YAML_MIME_TYPES
            else -> false
        }
        if (!compatible) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "format",
                    "format '$format' is not compatible with artifact mimeType '$mimeType'",
                )),
            )
        }
        return format
    }

    private fun validateTargetTable(args: JsonObject, metadata: ArtifactUploadMetadata) {
        // Follow-up AP 2: für Bundle-Artefakte wird `tables` (Plural) gegen
        // die persistierten `targetTables` aus der Init-Session validiert;
        // `table` (Singular) ist hier verboten.
        if (metadata.wireArtifactKind == ArtifactUploadInitHandler.WIRE_KIND_SEED_DATA_BUNDLE) {
            validateBundleTables(args, metadata)
            return
        }
        val requested = args.optString("table")
        val persisted = metadata.targetTable
        if (!requested.isNullOrBlank() && !persisted.isNullOrBlank() && requested != persisted) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "table",
                    "must match upload targetTable '$persisted'",
                )),
            )
        }
        if (requested.isNullOrBlank() && persisted.isNullOrBlank()) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "table",
                    "is required when upload metadata has no targetTable",
                )),
            )
        }
    }

    /**
     * Follow-up AP 2 — Bundle-Tabellen-Konsistenz.
     *
     * Plan §4 wortlaut: "`targetTables` in `ArtifactUploadMetadata` muss
     * mit Manifest und Tool-`tables` konsistent sein, falls der Upload
     * bereits Tabellenbindung mitbringt." Diese Validierung deckt den
     * Tool-vs-Init-Vertrag; die Manifest-Konsistenz wird im Runner
     * geprüft, sobald das Bundle extrahiert ist.
     */
    private fun validateBundleTables(args: JsonObject, metadata: ArtifactUploadMetadata) {
        val singleTable = args.optString("table")
        if (!singleTable.isNullOrBlank()) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "table",
                    "must not be set for bundle artifacts (use 'tables')",
                )),
            )
        }
        val tablesElement = args.get("tables")?.takeUnless { it.isJsonNull }
            ?: throw ValidationErrorException(
                listOf(ValidationViolation(
                    "tables",
                    "is required for bundle artifacts (wireArtifactKind=${metadata.wireArtifactKind})",
                )),
            )
        val requested = tablesElement.asJsonArray.map { it.asString }
        val callerBundleFormat = args.optString("bundleFormat")
        if (callerBundleFormat.isNullOrBlank()) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "bundleFormat",
                    "is required for bundle artifacts",
                )),
            )
        }
        if (metadata.bundleFormat != null && metadata.bundleFormat != callerBundleFormat) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "bundleFormat",
                    "must match upload bundleFormat '${metadata.bundleFormat}'",
                )),
            )
        }
        val persistedTables = metadata.targetTables
        if (persistedTables != null) {
            val callerNorm = requested.map { it.lowercase(Locale.US) }.toSortedSet()
            val initNorm = persistedTables.map { it.lowercase(Locale.US) }.toSortedSet()
            if (callerNorm != initNorm) {
                throw ValidationErrorException(
                    listOf(ValidationViolation(
                        "tables",
                        "must match upload intendedTables (case-insensitive)",
                    )),
                )
            }
        }
    }

    private fun inferFormat(mimeType: String): String? {
        val canonical = mimeType.lowercase(Locale.US).substringBefore(";").trim()
        return when {
            canonical in CSV_MIME_TYPES -> "csv"
            canonical in JSON_MIME_TYPES -> "json"
            canonical in YAML_MIME_TYPES -> "yaml"
            else -> null
        }
    }

    /**
     * Phase F § 8.7 (F.7 4/5): tenant-scoped Lookup im
     * [ConnectionReferenceStore]. Der Store liefert ausdruecklich
     * KEINE materialisierten JDBC-URLs (Plan: "secret-frei") — die
     * Pruefung dient nur der Existenz/Visibility, das eigentliche
     * Secret-Resolution passiert spaeter im Job-Runner gegen
     * `ConnectionSecretResolver`. Plan § 8.7 wortlaeufig:
     * "targetConnectionRef ohne aufloesbare ConnectionReference,
     * Secret oder Principal-Berechtigung -> RESOURCE_NOT_FOUND ...".
     */
    private fun resolveConnectionRef(refValue: String, tenantId: TenantId): String {
        val parsed = ServerResourceUri.parse(refValue)
        return when (parsed) {
            is ResourceUriParseResult.Invalid ->
                throw ValidationErrorException(
                    listOf(ValidationViolation(
                        "targetConnectionRef",
                        "invalid resource URI: ${parsed.reason}",
                    )),
                )
            is ResourceUriParseResult.Valid -> {
                if (parsed.uri.kind != ResourceKind.CONNECTIONS) {
                    throw ValidationErrorException(
                        listOf(ValidationViolation(
                            "targetConnectionRef",
                            "expected connections, got ${parsed.uri.kind.pathSegment}",
                        )),
                    )
                }
                if (parsed.uri.tenantId != tenantId) {
                    throw ValidationErrorException(
                        listOf(ValidationViolation(
                            "targetConnectionRef",
                            "tenant prefix mismatch: caller is ${tenantId.value}",
                        )),
                    )
                }
                connectionStore.findById(tenantId, parsed.uri.id)
                    ?: throw ResourceNotFoundException(parsed.uri)
                parsed.uri.id
            }
        }
    }

    /**
     * Phase F § 8.7 (F.7 4/5): optionaler `schemaRef`-Lookup.
     * Plan-Vertrag: "schemaRef ueber SchemaStore und
     * SchemaRefImportPreflightAdapter materialisieren; keine lokalen
     * Schema-Pfade aus Tool-Payloads verwenden."
     *
     * Die Tool-Phase prueft nur die Existenz im SchemaStore:
     * fehlende Refs bekommen RESOURCE_NOT_FOUND, lokale Pfade fallen
     * schon an der ServerResourceUri-Parse-Phase aus. Die
     * Schema-Validierung und Tabellenreihenfolge laufen spaeter im
     * Phase-F-Import-Worker ueber `SchemaRefImportPreflightAdapter`,
     * nachdem die tenant-scoped Schema-Bytes aus den Stores
     * materialisiert wurden.
     */
    private fun resolveSchemaRef(refValue: String, tenantId: TenantId) {
        val parsed = ServerResourceUri.parse(refValue)
        when (parsed) {
            is ResourceUriParseResult.Invalid ->
                throw ValidationErrorException(
                    listOf(ValidationViolation(
                        "schemaRef",
                        "invalid resource URI: ${parsed.reason}",
                    )),
                )
            is ResourceUriParseResult.Valid -> {
                if (parsed.uri.kind != ResourceKind.SCHEMAS) {
                    throw ValidationErrorException(
                        listOf(ValidationViolation(
                            "schemaRef",
                            "expected schemas, got ${parsed.uri.kind.pathSegment}",
                        )),
                    )
                }
                if (parsed.uri.tenantId != tenantId) {
                    throw ValidationErrorException(
                        listOf(ValidationViolation(
                            "schemaRef",
                            "tenant prefix mismatch: caller is ${tenantId.value}",
                        )),
                    )
                }
                schemaStore.findById(tenantId, parsed.uri.id)
                    ?: throw ResourceNotFoundException(parsed.uri)
            }
        }
    }

    /**
     * Phase F § 8.7 (F.7 4/5): MCP-spezifischer Import-Fingerprint.
     *
     * Plan-§-8.7-Pflichtfelder: artifactId/resourceUri, Artefakt-
     * sha256, persistente Upload-Metadaten (mimeType + filename),
     * targetConnectionRef, optional schemaRef, normalisierte
     * Import-Optionen, Tenant + Principal. Der Fingerprint darf
     * KEINE Temp-/Spool-Pfade, materialisierte JDBC-URLs, Connection-
     * Secrets oder lokalen CLI-Pfade enthalten — der Caller-supplied
     * Args-Block wird durch [ArtifactRecord.managedArtifact.sha256]
     * angereichert, der von der Server-Seite kommt und niemals aus
     * Tool-Eingaben ableitbar ist. Tenant + Principal binden
     * automatisch via [JobStartOrchestrator] /
     * [PayloadFingerprintService.BindContext].
     *
     * Der Plan erlaubt explizit NICHT die Wiederverwendung des
     * CLI-`ImportOptionsFingerprint`. Diese Implementierung baut
     * stattdessen auf den server-internen
     * [PayloadFingerprintService] auf — Tenant/Principal/Toolname
     * werden ueber die Phase-E-Bindung garantiert. Lokale CLI-Pfade
     * sind in der MCP-Pipeline strukturell ausgeschlossen
     * (F.7 (2/5) `JobStartInputValidator` weist freie JDBC-URLs +
     * lokale Pfade ab).
     */
    private fun enrichPayloadForFingerprint(
        args: JsonObject,
        record: ArtifactRecord,
        metadata: ArtifactUploadMetadata,
        effectiveFormat: String,
    ): dev.dmigrate.server.application.fingerprint.JsonValue.Obj {
        // Args-Klon, sodass der originale args-Tree (Wire-Eingabe)
        // nicht mutiert wird. Server-Seitige Felder werden mit
        // einem `_`-Prefix versehen, damit sie nie mit Caller-Feldern
        // kollidieren — Schema additionalProperties=false weist
        // `_artifactSha256` an der Wire-Layer ohnehin ab.
        val enriched = args.deepCopy()
        enriched.addProperty("format", effectiveFormat)
        if (enriched.optString("table").isNullOrBlank()) {
            metadata.targetTable?.let { enriched.addProperty("table", it) }
        }
        // Follow-up AP 2: Bundle-Felder kanonisch in den Fingerprint
        // einrechnen (sortiert + lowercased), damit identische Tabellen-
        // Listen in unterschiedlicher Reihenfolge denselben Fingerprint
        // ergeben.
        if (metadata.wireArtifactKind == ArtifactUploadInitHandler.WIRE_KIND_SEED_DATA_BUNDLE) {
            val tablesElement = enriched.get("tables")
            if (tablesElement != null && tablesElement.isJsonArray) {
                val normalized = tablesElement.asJsonArray
                    .map { it.asString.lowercase(Locale.US) }
                    .distinct()
                    .sorted()
                val canonical = com.google.gson.JsonArray()
                normalized.forEach { canonical.add(it) }
                enriched.add("tables", canonical)
            }
            metadata.bundleFormat?.let { enriched.addProperty("_bundleFormat", it) }
        }
        enriched.addProperty("_artifactSha256", record.managedArtifact.sha256)
        enriched.addProperty("_artifactMimeType", record.managedArtifact.contentType)
        enriched.addProperty("_artifactFilename", record.managedArtifact.filename)
        enriched.addProperty("_artifactSizeBytes", record.managedArtifact.sizeBytes)
        enriched.addProperty("_uploadIntent", metadata.uploadIntent)
        enriched.addProperty("_wireArtifactKind", metadata.wireArtifactKind)
        enriched.addProperty("_sourceUploadSessionId", metadata.sourceUploadSessionId)
        metadata.policyFingerprint?.let { enriched.addProperty("_policyFingerprint", it) }
        return JobStartHandlerSupport.toJsonValueObj(enriched)
    }

    private sealed interface ArtifactSource {
        data class Id(val value: String) : ArtifactSource
        data class Ref(val value: String) : ArtifactSource
    }

    companion object {
        const val TOOL_NAME: String = "data_import_start"
        const val OPERATION: String = "data_import"
        const val DEFAULT_JOB_RETENTION_SECONDS: Long = 24 * 60 * 60
        // Plan § 6.1: chunkSize "positive Ganzzahl bis 10000".
        const val MAX_CHUNK_SIZE: Long = 10_000
        private val CSV_MIME_TYPES = setOf("text/csv", "application/csv", "application/vnd.ms-excel")
        private val JSON_MIME_TYPES = setOf("application/json", "text/json", "application/x-ndjson")
        private val YAML_MIME_TYPES = setOf("application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml")
        private val IMPORT_ON_ERROR_VALUES = setOf("abort", "skip", "log")
        private val IMPORT_ON_CONFLICT_VALUES = setOf("abort", "skip", "update")
        private val IMPORT_TRIGGER_MODE_VALUES = setOf("fire", "disable", "strict")
    }
}
