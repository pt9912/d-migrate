package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
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
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ArtifactStore
import dev.dmigrate.server.ports.ConnectionReferenceStore
import dev.dmigrate.server.ports.SchemaStore
import java.time.Clock

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
 * **Carve-out F.7 (2/5)**: Artefakt-Eignung (`kind=UPLOAD_INPUT`,
 * `uploadIntent=job_input`, wireArtifactKind-Matrix) und
 * `table`/`tables`/`targetTable`-Semantik werden in F.7 (3/5)
 * eingebaut. ConnectionRef-Resolution + SchemaRef-Resolution +
 * MCP-Import-Fingerprint folgen in F.7 (4/5). Production-Wiring +
 * E2E-Test in F.7 (5/5).
 */
internal class DataImportStartHandler(
    private val orchestrator: JobStartOrchestrator,
    private val artifactStore: ArtifactStore,
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
        // Phase F § 6.1 (F.7 3/5): Artefakt-Eignungsmatrix.
        // Lookup vor Idempotency, damit "unbekanntes Artefakt" und
        // "falsche Kind/Intent" deterministisch ohne Job-/SyncEffect-
        // Reservierung gemeldet werden.
        val tenantId = context.principal.effectiveTenantId
        val record = resolveArtifact(artifactSource, tenantId)
        validateArtifactEligibility(record)
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
            payload = enrichPayloadForFingerprint(args, record),
            refs = refs,
            now = now,
            auditFields = context.auditFields,
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

    /**
     * Phase F § 6.1 (F.7 3/5): table/tables-Topologie.
     *
     * - Beide gleichzeitig: VALIDATION_ERROR (mehrdeutig).
     * - `tables`: in Phase F nicht erlaubt — Plan § 6.1 wortlaeufig
     *   "ImportInput.Directory und tables fuer Mehrtabellenimporte
     *   sind in Phase F nur erlaubt, wenn ein Bundle-Format ...
     *   explizit eingefuehrt wird. Solange dieses Bundle-Format
     *   nicht definiert ist, sind tables und Directory-/
     *   Mehrtabellen-Topologien fuer Upload-Importe
     *   VALIDATION_ERROR." `table` allein bleibt zulaessig.
     * - `tables` als leeres Array oder mit leeren Strings:
     *   VALIDATION_ERROR (Plan: "leere oder syntaktisch ungueltige
     *   tables -> VALIDATION_ERROR").
     */
    private fun validateTableTopology(args: JsonObject) {
        val table = args.optString("table")
        val tablesElement = args.get("tables")?.takeUnless { it.isJsonNull }

        if (!table.isNullOrBlank() && tablesElement != null) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "table",
                    "'table' and 'tables' are mutually exclusive",
                )),
            )
        }
        if (tablesElement != null) {
            // Plan § 6.1: Phase F hat noch kein Bundle-Format ->
            // `tables` immer abweisen. Defensiv inklusive Form-
            // Check (leere Liste / leere Strings).
            if (!tablesElement.isJsonArray) {
                throw ValidationErrorException(
                    listOf(ValidationViolation("tables", "must be an array of strings")),
                )
            }
            val arr = tablesElement.asJsonArray
            if (arr.isEmpty) {
                throw ValidationErrorException(
                    listOf(ValidationViolation("tables", "must not be empty")),
                )
            }
            arr.forEach { entry ->
                val isString = entry.isJsonPrimitive && entry.asJsonPrimitive.isString
                if (!isString || entry.asString.isBlank()) {
                    throw ValidationErrorException(
                        listOf(ValidationViolation("tables", "items must be non-blank strings")),
                    )
                }
            }
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "tables",
                    "Bundle-/Directory-Imports require an explicit bundle format " +
                        "(not yet defined in Phase F); use 'table' for single-file artifacts",
                )),
            )
        }
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
     * **Carve-out F.7 (3/5)**: `wireArtifactKind` (schema, ddl,
     * transform-script, rules, seed-data, generic) und
     * `format`/`mimeType`-Kompatibilitaet werden in F.7 (4/5)
     * abgedeckt — sie benoetigen einen ArtifactUploadMetadataStore
     * oder eine Erweiterung von ArtifactRecord.
     */
    private fun validateArtifactEligibility(record: ArtifactRecord) {
        if (record.kind != ArtifactKind.UPLOAD_INPUT) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "artifactId",
                    "import requires artifactKind=UPLOAD_INPUT, got ${record.kind.name} " +
                        "(read-only schema-staging artefacts cannot be imported)",
                )),
            )
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
     * **Carve-out F.7 (4/5)**: der `SchemaRefImportPreflightAdapter`
     * (Schema-Validierung + Tabellenreihenfolge) ist Runner-side
     * Concern und wird im Phase-F-Import-Worker eingehaengt.
     * Hier wird nur die Existenz im SchemaStore geprueft — fehlende
     * Refs bekommen RESOURCE_NOT_FOUND, lokale Pfade fallen schon
     * an der ServerResourceUri-Parse-Phase aus.
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
    ): dev.dmigrate.server.application.fingerprint.JsonValue.Obj {
        // Args-Klon, sodass der originale args-Tree (Wire-Eingabe)
        // nicht mutiert wird. Server-Seitige Felder werden mit
        // einem `_`-Prefix versehen, damit sie nie mit Caller-Feldern
        // kollidieren — Schema additionalProperties=false weist
        // `_artifactSha256` an der Wire-Layer ohnehin ab.
        val enriched = args.deepCopy()
        enriched.addProperty("_artifactSha256", record.managedArtifact.sha256)
        enriched.addProperty("_artifactMimeType", record.managedArtifact.contentType)
        enriched.addProperty("_artifactFilename", record.managedArtifact.filename)
        enriched.addProperty("_artifactSizeBytes", record.managedArtifact.sizeBytes)
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
    }
}
