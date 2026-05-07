package dev.dmigrate.mcp.registry

import com.google.gson.JsonObject
import dev.dmigrate.cli.commands.FilterParseException
import dev.dmigrate.cli.commands.parseFilter
import dev.dmigrate.mcp.registry.JsonArgs.optString
import dev.dmigrate.mcp.registry.JsonArgs.requireString
import dev.dmigrate.server.application.error.ResourceNotFoundException
import dev.dmigrate.server.application.error.ValidationErrorException
import dev.dmigrate.server.application.error.ValidationViolation
import dev.dmigrate.server.application.job.JobStartOrchestrator
import dev.dmigrate.server.application.job.JobStartRequest
import dev.dmigrate.server.application.job.RefField
import dev.dmigrate.server.core.job.JobRecord
import dev.dmigrate.server.core.job.JobStatus
import dev.dmigrate.server.core.job.JobVisibility
import dev.dmigrate.server.core.job.ManagedJob
import dev.dmigrate.server.core.principal.TenantId
import dev.dmigrate.server.core.resource.ResourceKind
import dev.dmigrate.server.core.resource.ResourceUriParseResult
import dev.dmigrate.server.core.resource.ServerResourceUri
import dev.dmigrate.server.ports.ConnectionReferenceStore
import java.time.Clock

/**
 * Phase F § 6.2 + § 8.8 (F.8 2/4) — `data_transfer_start`-Handler.
 *
 * Pre-Idempotency-Validation (Plan § 8.8 wortlaeufig "vor
 * Idempotency ohne Store-Write"):
 *
 * - `idempotencyKey` Pflicht (vom Phase-E
 *   [dev.dmigrate.server.application.job.JobStartInputValidator]
 *   geprueft).
 * - `sourceConnectionRef` + `targetConnectionRef` als RefField mit
 *   `ResourceKind.CONNECTIONS` — der Validator weist freie
 *   JDBC-URLs, ungueltige URI-Syntax und Tenant-Mismatch ab.
 * - `chunkSize` muss in [1, 10000] liegen.
 * - `sinceColumn` und `since` paarweise: beide fehlen ODER beide
 *   gesetzt (Plan § 8.8 wortlaeufig).
 * - `sinceColumn` muss CLI-Identifier-Format folgen (alphanumerisch
 *   plus `.` und `_`; konservativ).
 * - `filter` darf nicht blank sein (Plan: "blanke oder ungueltige
 *   Filter liefern VALIDATION_ERROR").
 *
 * Phase-E Job-Pipeline:
 *
 * - [JobStartOrchestrator] uebernimmt Idempotency-Reservierung,
 *   Policy-Decision, Quota-Reservierung und durable Job-Anlage.
 *
 * ConnectionRefs werden tenant-scoped gegen den
 * [dev.dmigrate.server.ports.ConnectionReferenceStore] aufgeloest.
 * `filter` wird vor dem Fingerprint in eine stabile, whitespace- und
 * keyword-case-unabhaengige Form gebracht; der Runner fuehrt spaeter
 * erst die datenbankspezifische Filterauswertung aus.
 */
internal class DataTransferStartHandler(
    private val orchestrator: JobStartOrchestrator,
    private val connectionStore: ConnectionReferenceStore,
    private val clock: Clock,
    private val jobRetentionSeconds: Long = DEFAULT_JOB_RETENTION_SECONDS,
) : ToolHandler {

    override fun handle(context: ToolCallContext): ToolCallOutcome {
        val args = JsonArgs.requireObject(context.arguments)
        val idempotencyKey = args.optString("idempotencyKey")
        val approvalToken = args.optString("approvalToken")
        val sourceConnectionRef = args.requireString("sourceConnectionRef")
        val targetConnectionRef = args.requireString("targetConnectionRef")
        validateChunkSize(args)
        val canonicalFilter = validateFilter(args)
        validateSincePair(args)

        val tenantId = context.principal.effectiveTenantId
        // Phase F § 8.8 (F.8 3/4): Existenz-/Tenant-Lookup VOR der
        // Idempotency-Reservierung. Plan-Wortlaut: "ConnectionRef
        // ohne aufloesbare Secret-/Provider-Referenz oder Principal-
        // Berechtigung -> RESOURCE_NOT_FOUND". Beide Refs werden
        // separat geprueft, sodass der Caller den genauen Fehler-
        // Pfad sieht. ConnectionReferenceStore liefert KEINE
        // materialisierten JDBC-URLs (Plan: "secret-frei").
        resolveConnectionRef(sourceConnectionRef, tenantId, "sourceConnectionRef")
        resolveConnectionRef(targetConnectionRef, tenantId, "targetConnectionRef")
        val now = clock.instant()

        val refs = listOf(
            RefField(
                name = "sourceConnectionRef",
                value = sourceConnectionRef,
                expectedKind = ResourceKind.CONNECTIONS,
            ),
            RefField(
                name = "targetConnectionRef",
                value = targetConnectionRef,
                expectedKind = ResourceKind.CONNECTIONS,
            ),
        )

        val request = JobStartRequest(
            toolName = TOOL_NAME,
            tenantId = tenantId,
            callerId = context.principal.principalId,
            idempotencyKey = idempotencyKey,
            approvalToken = approvalToken,
            payload = JobStartHandlerSupport.toJsonValueObj(canonicalPayload(args, canonicalFilter)),
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
     * Plan § 6.2: `chunkSize` ist eine "positive Ganzzahl bis 10000".
     * Schema sichert `minimum=1`; die Obergrenze liegt im Handler
     * als Defense in Depth (analog zu F.7 (2/5)).
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
     * Plan § 8.8: "blanker oder ungueltiger filter -> VALIDATION_ERROR".
     * Der Rueckgabewert ist die kanonische Form fuer den
     * MCP-Transfer-Fingerprint; datenbankspezifisches SQL-/DSL-Binding
     * bleibt Runner-Aufgabe.
     */
    private fun validateFilter(args: JsonObject): String? {
        val raw = args.get("filter")?.takeUnless { it.isJsonNull } ?: return null
        val primitive = raw as? com.google.gson.JsonPrimitive
        if (primitive == null || !primitive.isString) {
            throw ValidationErrorException(
                listOf(ValidationViolation("filter", "must be a string")),
            )
        }
        if (primitive.asString.isBlank()) {
            throw ValidationErrorException(
                listOf(ValidationViolation("filter", "must not be blank")),
            )
        }
        val parsed = try {
            parseFilter(stripLeadingWhere(primitive.asString))
        } catch (e: FilterParseException) {
            throw ValidationErrorException(
                listOf(ValidationViolation("filter", e.parseError.message)),
            )
        }
        return parsed?.canonical
    }

    private fun canonicalPayload(args: JsonObject, canonicalFilter: String?): JsonObject {
        val payload = args.deepCopy()
        if (canonicalFilter != null) {
            payload.addProperty("filter", canonicalFilter)
        }
        return payload
    }

    private fun stripLeadingWhere(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.regionMatches(0, "where", 0, 5, ignoreCase = true) &&
            (trimmed.length == 5 || trimmed[5].isWhitespace())
        ) {
            trimmed.drop(5).trim()
        } else {
            trimmed
        }
    }

    /**
     * Plan § 8.8: "sinceColumn und since paarweise validieren: beide
     * fehlen oder beide gesetzt." `sinceColumn` muss CLI-Identifier-
     * Format folgen — konservativ alphanumerisch plus `.` und `_`,
     * sodass `schema.column` und `column_name` zulaessig sind, aber
     * SQL-Injection (`'; DROP`-Patterns) ausgeschlossen ist.
     */
    private fun validateSincePair(args: JsonObject) {
        val sinceColumn = args.optString("sinceColumn")
        val since = args.optString("since")
        if (sinceColumn.isNullOrBlank() && since.isNullOrBlank()) return
        if (sinceColumn.isNullOrBlank()) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "sinceColumn",
                    "is required when 'since' is set",
                )),
            )
        }
        if (since.isNullOrBlank()) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "since",
                    "is required when 'sinceColumn' is set",
                )),
            )
        }
        if (!IDENTIFIER_PATTERN.matches(sinceColumn)) {
            throw ValidationErrorException(
                listOf(ValidationViolation(
                    "sinceColumn",
                    "must be a valid SQL identifier (alphanumeric, '.', '_')",
                )),
            )
        }
    }

    /**
     * Phase F § 8.8 (F.8 3/4): tenant-scoped ConnectionRef-Lookup
     * mit `field`-spezifischer Fehlerausgabe (sourceConnectionRef vs
     * targetConnectionRef), sodass der Caller den genauen Pfad sieht
     * statt einer generischen "Connection not found"-Antwort.
     *
     * Plan § 8.8 wortlaeufig:
     * - tenant-scoped Validierung der ConnectionRef-URI
     * - findById im [ConnectionReferenceStore] -> RESOURCE_NOT_FOUND
     * - keine Secret-/JDBC-Materialisierung im Tool-Pfad
     *
     * MCP-Transfer-Fingerprint: der Plan-§-8.8-Fingerprint-Vertrag
     * (sourceConnectionRef, targetConnectionRef, kanonische
     * Filterform, normalisierte since-Optionen, weitere
     * normalisierte Transfer-Optionen, Tenant + Principal) wird durch
     * den existierenden
     * [dev.dmigrate.server.application.fingerprint.PayloadFingerprintService]
     * via JobStartOrchestrator gebildet. Die `filter`-Roh-Eingabe wird
     * vorher durch [canonicalizeFilter] ersetzt; Tenant + Principal
     * kommen ueber `BindContext`.
     */
    private fun resolveConnectionRef(refValue: String, tenantId: TenantId, fieldName: String) {
        val parsed = ServerResourceUri.parse(refValue)
        when (parsed) {
            is ResourceUriParseResult.Invalid ->
                throw ValidationErrorException(
                    listOf(ValidationViolation(
                        fieldName,
                        "invalid resource URI: ${parsed.reason}",
                    )),
                )
            is ResourceUriParseResult.Valid -> {
                if (parsed.uri.kind != ResourceKind.CONNECTIONS) {
                    throw ValidationErrorException(
                        listOf(ValidationViolation(
                            fieldName,
                            "expected connections, got ${parsed.uri.kind.pathSegment}",
                        )),
                    )
                }
                if (parsed.uri.tenantId != tenantId) {
                    throw ValidationErrorException(
                        listOf(ValidationViolation(
                            fieldName,
                            "tenant prefix mismatch: caller is ${tenantId.value}",
                        )),
                    )
                }
                connectionStore.findById(tenantId, parsed.uri.id)
                    ?: throw ResourceNotFoundException(parsed.uri)
            }
        }
    }

    companion object {
        const val TOOL_NAME: String = "data_transfer_start"
        const val OPERATION: String = "data_transfer"
        const val DEFAULT_JOB_RETENTION_SECONDS: Long = 24 * 60 * 60
        // Plan § 6.2: chunkSize "positive Ganzzahl bis 10000".
        const val MAX_CHUNK_SIZE: Long = 10_000
        // CLI-Identifier-konsistente Validierung — alphanumerisch
        // plus `.` (qualified columns) und `_` (snake_case).
        private val IDENTIFIER_PATTERN: Regex = Regex("""^[A-Za-z_][A-Za-z0-9_.]*$""")
    }
}
